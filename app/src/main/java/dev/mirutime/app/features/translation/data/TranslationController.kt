package dev.mirutime.app.features.translation.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Hard cap for one provider call; a hung request must never leave Loading forever. */
internal val DefaultChapterTimeout = 120.seconds

enum class ChapterTranslationStatus { Loading, Ready, Failed }

/** Actionable failures worth naming instead of generic "translation failed". */
enum class ChapterTranslationFailure { LocalNetworkRequired, CleartextNotAllowed }

data class ChapterTranslation(
    val status: ChapterTranslationStatus,
    val translations: Map<Int, String>,
    val configKey: String,
    // Owning book: per-novel cleanup rules are resolved from it at read time.
    val bookUrl: String = "",
    val failure: ChapterTranslationFailure? = null,
)

/**
 * Runs chapter translations one at a time and exposes their state by chapter
 * URL. Successful results are cached, failures stay in the state so the reader
 * can retry with the same request.
 */
class TranslationController(
    private val engine: TranslationEngine,
    private val scope: CoroutineScope,
    private val cache: ChapterTranslationCache? = null,
    private val chapterTimeout: Duration = DefaultChapterTimeout,
) {
    private val _state = MutableStateFlow<Map<String, ChapterTranslation>>(emptyMap())
    val state: StateFlow<Map<String, ChapterTranslation>> = _state.asStateFlow()

    private data class PendingRequest(
        val request: TranslationRequest,
        val force: Boolean,
        val previous: Map<Int, String>? = null,
        val previousKey: String? = null,
        val outcome: CompletableDeferred<Boolean>? = null,
        val queueId: Long = 0,
    )

    private val pending = ArrayDeque<PendingRequest>()
    private val queued = TranslationQueueBookkeeping()
    // submit() is called from UI, bulk translation and the reader; the worker
    // and forget() touch the same collections from their own contexts.
    private val queueLock = Any()
    // shortcut: one FIFO worker and a 12-entry cache; the translator already
    // retries, so no priorities, backoff or circuit breaker here.
    private val memoryCache = LinkedHashMap<String, Map<Int, String>>()
    // configKey -> chapterUrl: forget() must be able to drop L1 entries, and
    // configKey starts with the BOOK url, so a chapter-url prefix never matched.
    private val memoryCacheChapter = HashMap<String, String>()
    // Bumped by forget(): an in-flight worker from the old epoch must not
    // persist or publish a translation the user asked to discard.
    private val epochs = LinkedHashMap<String, Int>()
    // Per-chapter job of the request the worker is running right now. forget()
    // cancels it, so a re-translate does not wait behind its own stale request.
    private val inflightLock = Any()
    private val inflight = HashMap<String, Job>()
    private var worker: Job? = null

    /** Returns false when the chapter is already current or queued unchanged. */
    fun submit(request: TranslationRequest): Boolean {
        val key = translationConfigKey(request)
        val current = _state.value[request.chapterUrl]
        val alreadyCurrent = current != null &&
            current.configKey == key &&
            current.status != ChapterTranslationStatus.Failed
        if (alreadyCurrent) return false
        val queueKey = queueKey(request.chapterUrl, key)

        synchronized(queueLock) {
            if (queued.isQueued(queueKey)) return false

            val cached = memoryCache[key]
            if (cached != null) {
                MiruLog.event(
                    "translation",
                    "chapter-cache",
                    "hit" to true,
                    "kind" to "memory",
                    "chapter" to chapterHash(request.chapterUrl),
                    "blocks" to cached.size,
                )
                _state.update {
                    it + (request.chapterUrl to ChapterTranslation(
                        status = ChapterTranslationStatus.Ready,
                        translations = cached,
                        configKey = key,
                        bookUrl = request.bookUrl,
                    ))
                }
                return true
            }

            _state.update {
                it + (request.chapterUrl to ChapterTranslation(
                    status = ChapterTranslationStatus.Loading,
                    translations = emptyMap(),
                    configKey = key,
                    bookUrl = request.bookUrl,
                ))
            }
            val queueId = queued.claim(queueKey)
            pending.addLast(PendingRequest(request, force = false, queueId = queueId))
            startWorkerLocked()
        }
        MiruLog.event(
            "translation",
            "chapter-submit",
            "chapter" to chapterHash(request.chapterUrl),
            "provider" to request.provider.name,
            "units" to request.units.size,
        )
        return true
    }

    /**
     * Re-runs one chapter while keeping the existing translation on screen.
     *
     * The ready text is captured only when the config identity is unchanged and
     * is published as Ready while the retry runs. A changed config publishes
     * Loading and never relabels old text. A failure restores only same-identity
     * text (same prompt/pipeline, any revision), otherwise publishes Failed; it
     * never writes a FAILED row.
     */
    suspend fun submitForced(request: TranslationRequest): Boolean {
        val key = translationConfigKey(request)
        val current = _state.value[request.chapterUrl]
        val previousKey = current?.configKey
        val captured = current
            ?.takeIf { it.status == ChapterTranslationStatus.Ready }
            ?.translations
        // Old text belongs to the old config; keep it only when the identity
        // is unchanged, otherwise publish Loading and never relabel it.
        val previous = captured.takeIf { previousKey == key }
        forget(listOf(request.chapterUrl))
        _state.update {
            it + (request.chapterUrl to ChapterTranslation(
                status = if (previous != null) {
                    ChapterTranslationStatus.Ready
                } else {
                    ChapterTranslationStatus.Loading
                },
                translations = previous.orEmpty(),
                configKey = key,
                bookUrl = request.bookUrl,
            ))
        }
        val outcome = CompletableDeferred<Boolean>()
        val queueKey = queueKey(request.chapterUrl, key)
        val queueId = synchronized(queueLock) { queued.claim(queueKey) }
        // A manual retry is the user's foreground action; do not put it
        // behind a bulk queue.
        val entry = PendingRequest(
            request = request,
            force = true,
            previous = previous,
            previousKey = previousKey,
            outcome = outcome,
            queueId = queueId,
        )
        synchronized(queueLock) {
            pending.addFirst(entry)
            startWorkerLocked()
        }
        // A worker that already finished this request is not a dead worker:
        // only abandon when the outcome is still open.
        val alive = synchronized(queueLock) { worker?.isActive == true }
        if (!alive && !outcome.isCompleted) {
            // The claim is released under the same lock that owns the queue:
            // `owners` is a plain map and must not be touched from outside.
            synchronized(queueLock) {
                pending.removeAll { it.outcome === outcome }
                queued.release(queueKey, queueId)
            }
            abandon(entry)
            outcome.complete(false)
        }
        MiruLog.event(
            "translation",
            "chapter-submit",
            "chapter" to chapterHash(request.chapterUrl),
            "provider" to request.provider.name,
            "units" to request.units.size,
            "forced" to true,
        )
        return outcome.await()
    }

    /**
     * Publishes an already cached chapter before the reader renders it: memory
     * first, then Room. Returns false on a miss without setting Loading or
     * starting the engine, so the caller keeps its normal submit fallback.
     */
    suspend fun preload(request: TranslationRequest): Boolean {
        val key = translationConfigKey(request)
        val current = _state.value[request.chapterUrl]
        if (current?.configKey == key) return current.status == ChapterTranslationStatus.Ready
        val fromMemory = synchronized(queueLock) { memoryCache[key] }
        val cached = fromMemory ?: cacheRead { cache?.load(request) }
        if (cached == null) return false
        if (fromMemory == null) remember(key, request.chapterUrl, cached)
        _state.update {
            it + (request.chapterUrl to ChapterTranslation(
                status = ChapterTranslationStatus.Ready,
                translations = cached,
                configKey = key,
                bookUrl = request.bookUrl,
            ))
        }
        MiruLog.event(
            "translation",
            "chapter-cache",
            "hit" to true,
            "kind" to if (fromMemory != null) "memory" else "disk",
            "chapter" to chapterHash(request.chapterUrl),
            "blocks" to cached.size,
        )
        return true
    }

    /** Drops live state/cache entries after their persisted rows were deleted. */
    fun forget(chapterUrls: Collection<String>) {
        if (chapterUrls.isEmpty()) return
        val urls = chapterUrls.toSet()
        _state.update { state -> state - urls }
        val dropped = synchronized(queueLock) {
            val removed = pending.filter { it.request.chapterUrl in urls }
            pending.removeAll { it.request.chapterUrl in urls }
            queued.removeAll { key -> urls.any { key.startsWith("$it\u0000") } }
            memoryCacheChapter.keys
                .filter { memoryCacheChapter[it] in urls }
                .forEach { key ->
                    memoryCache.remove(key)
                    memoryCacheChapter.remove(key)
                }
            urls.forEach { epochs.merge(it, 1, Int::plus) }
            // Snapshot the in-flight set outside its own lock so the lock order
            // stays inflightLock -> queueLock (forget holds only queueLock here).
            removed
        }
        // Cancel first, then prune: the prune must see the chapters that are
        // still running so it never drops their epoch.
        val running = synchronized(inflightLock) {
            urls.forEach { inflight.remove(it)?.cancel() }
            // Forgotten chapters keep their epoch until the prune: a cancelled
            // worker must never see epoch 0 again and publish late results.
            inflight.keys.toSet() + urls
        }
        synchronized(queueLock) { pruneEpochs(epochs, running, EPOCH_LIMIT) }
        // A forced caller is suspended on its outcome; dropping the queue entry
        // without completing it would leave the reader's spinner forever.
        dropped.forEach { it.outcome?.complete(false) }
    }

    private fun chapterHash(url: String): String = url.hashCode().toString(16)

    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    val entry = synchronized(queueLock) {
                        if (pending.isEmpty()) null else pending.removeFirst()
                    } ?: break
                    val request = entry.request
                    val key = translationConfigKey(request)
                    val epoch = epochOf(request.chapterUrl)
                    val job = scope.launch { runRequest(entry, key, epoch) }
                    synchronized(inflightLock) { inflight[request.chapterUrl] = job }
                    try {
                        job.join()
                    } finally {
                        synchronized(inflightLock) { inflight.remove(request.chapterUrl, job) }
                    }
                }
            } finally {
                // Null the worker before abandoning: a reentrant submit from a
                // completed outcome must be able to start a fresh worker. The
                // defereds are completed after the lock so caller code never
                // runs inside the controller's critical section.
                val abandoned = ArrayList<PendingRequest>()
                synchronized(queueLock) {
                    worker = null
                    if (pending.isNotEmpty() && scope.isActive) {
                        // A record was queued while this worker was stopping:
                        // hand it to a fresh worker instead of dropping it.
                        // Its claim stays with the entry.
                        startWorkerLocked()
                    } else {
                        val remaining = pending.toList()
                        pending.clear()
                        remaining.forEach { entry ->
                            val key = translationConfigKey(entry.request)
                            queued.release(queueKey(entry.request.chapterUrl, key), entry.queueId)
                            abandon(entry)
                            abandoned += entry
                        }
                    }
                }
                abandoned.forEach { it.outcome?.complete(false) }
            }
        }
    }

    private suspend fun runRequest(
        entry: PendingRequest,
        key: String,
        epoch: Int,
    ) {
        val request = entry.request
        fun forgotten(): Boolean = epochOf(request.chapterUrl) != epoch

        // A cancelled scope means the screen is gone: the cancellation must
        // propagate. An inner cancellation (a cancelled cache read) must not
        // leave the chapter in Loading forever - every later submit would be
        // a no-op then.
        suspend fun handleInnerCancellation(cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            MiruLog.event(
                "translation",
                "request-cancelled",
                "chapter" to chapterHash(request.chapterUrl),
            )
            if (!forgotten()) fail(request.chapterUrl, key, request.bookUrl)
        }

        try {
            if (!entry.force) {
                val disk = cacheRead { cache?.load(request) }
                if (disk != null && !forgotten()) {
                    MiruLog.event(
                        "translation",
                        "chapter-cache",
                        "hit" to true,
                        "kind" to "disk",
                        "chapter" to chapterHash(request.chapterUrl),
                        "blocks" to disk.size,
                    )
                    remember(key, request.chapterUrl, disk)
                    _state.update {
                        it + (request.chapterUrl to ChapterTranslation(
                            status = ChapterTranslationStatus.Ready,
                            translations = disk,
                            configKey = key,
                            bookUrl = request.bookUrl,
                        ))
                    }
                    return
                }
            }
            val outcome = try {
                withTimeout(chapterTimeout) {
                    MiruLog.event(
                        "translation",
                        "chapter-cache",
                        "hit" to false,
                        "kind" to "disk",
                        "chapter" to chapterHash(request.chapterUrl),
                    )
                    engine.translateChapter(request)
                }
            } catch (timeout: TimeoutCancellationException) {
                if (forgotten()) return
                // Bounded attempt: a normal request persists the failure;
                // a forced one keeps whatever the user already had.
                MiruLog.w(
                    "translation",
                    "chapter timeout chapterHash=${chapterHash(request.chapterUrl)} " +
                        "provider=${request.provider.name} " +
                        "timeoutMs=${chapterTimeout.inWholeMilliseconds}",
                )
                handleFailure(entry, key, null)
                return
            } catch (cancellation: CancellationException) {
                handleInnerCancellation(cancellation)
                return
            } catch (error: Exception) {
                MiruLog.e("translation", "chapter translation failed", error)
                null
            }
            when (outcome) {
                null -> {
                    if (forgotten()) return
                    MiruLog.event(
                        "translation",
                        "chapter-result",
                        "ok" to false,
                        "chapter" to chapterHash(request.chapterUrl),
                    )
                    handleFailure(entry, key, null)
                }
                is Outcome.Ok -> {
                    if (forgotten()) return
                    var saved = false
                    cacheWrite {
                        cache?.save(request, outcome.value)
                        saved = true
                    }
                    MiruLog.event(
                        "translation",
                        "chapter-result",
                        "ok" to true,
                        "chapter" to chapterHash(request.chapterUrl),
                        "blocks" to outcome.value.translations.size,
                        "dbSaved" to saved,
                    )
                    // Publish only while this key still owns the chapter: a
                    // newer config may have taken the screen meanwhile, and the
                    // saved row must not be relabelled under its key. The
                    // request still succeeded, so the caller is not told it
                    // failed (the text is already persisted).
                    val live = _state.value[request.chapterUrl]
                    if (live == null || live.configKey == key) {
                        remember(key, request.chapterUrl, outcome.value.translations)
                        _state.update {
                            it + (request.chapterUrl to ChapterTranslation(
                                status = ChapterTranslationStatus.Ready,
                                translations = outcome.value.translations,
                                configKey = key,
                                bookUrl = request.bookUrl,
                            ))
                        }
                    }
                    entry.outcome?.complete(true)
                }
                is Outcome.Err -> {
                    if (forgotten()) return
                    MiruLog.event(
                        "translation",
                        "chapter-result",
                        "ok" to false,
                        "chapter" to chapterHash(request.chapterUrl),
                    )
                    handleFailure(entry, key, outcome.error)
                }
            }
        } catch (cancellation: CancellationException) {
            // The cache read was cancelled: the chapter must not stay in
            // Loading, or no later submit would ever start.
            handleInnerCancellation(cancellation)
        } finally {
            synchronized(queueLock) {
                queued.release(queueKey(request.chapterUrl, key), entry.queueId)
            }
            entry.outcome?.complete(false)
        }
    }

    private suspend fun handleFailure(
        entry: PendingRequest,
        key: String,
        error: MiruError?,
    ) {
        val request = entry.request
        if (entry.force) {
            // Forced retry: the previous translation wins over a FAILED row.
            // A known different config must not inherit it; an empty state may
            // restore only a row with the same prompt/pipeline (any revision).
            val sameKey = entry.previousKey == key
            val previous = when {
                entry.previousKey == null -> cacheRead { cache?.loadSameConfig(request) }
                sameKey -> entry.previous
                else -> null
            }
            if (previous != null && isCurrent(request.chapterUrl, key)) {
                // Only the text of this exact revision may enter L1: a row
                // loaded by config alone can belong to another revision.
                if (sameKey) remember(key, request.chapterUrl, previous)
                _state.update {
                    it + (request.chapterUrl to ChapterTranslation(
                        status = ChapterTranslationStatus.Ready,
                        translations = previous,
                        configKey = key,
                        bookUrl = request.bookUrl,
                    ))
                }
                return
            }
            fail(request.chapterUrl, key, request.bookUrl, error)
            return
        }
        // A regular retry may fail because the source changed. Keep the usable
        // text of the same prompt on screen but stay Failed, so the badge
        // explains it and the chapter remains retryable. A row made with
        // another prompt is a different translation and must not be shown.
        val previous = cacheRead { cache?.loadSameConfig(request) }
        if (previous == null) {
            cacheWrite { cache?.markFailed(request) }
            fail(request.chapterUrl, key, request.bookUrl, error)
        } else if (isCurrent(request.chapterUrl, key)) {
            _state.update {
                it + (request.chapterUrl to ChapterTranslation(
                    status = ChapterTranslationStatus.Failed,
                    translations = previous,
                    configKey = key,
                    bookUrl = request.bookUrl,
                    failure = failureFor(error),
                ))
            }
        }
    }

    private fun failureFor(error: MiruError?): ChapterTranslationFailure? = when (error) {
        is TranslationError.LocalNetworkRequired -> ChapterTranslationFailure.LocalNetworkRequired
        is TranslationError.CleartextNotAllowed -> ChapterTranslationFailure.CleartextNotAllowed
        else -> null
    }

    /** Restores or fails a queued entry the worker could not process. */
    private fun abandon(entry: PendingRequest) {
        if (entry.outcome?.isCompleted == true) return
        val request = entry.request
        val key = translationConfigKey(request)
        if (entry.force && entry.previousKey == key && entry.previous != null &&
            isCurrent(request.chapterUrl, key)
        ) {
            remember(key, request.chapterUrl, entry.previous)
            _state.update {
                it + (request.chapterUrl to ChapterTranslation(
                    status = ChapterTranslationStatus.Ready,
                    translations = entry.previous,
                    configKey = key,
                    bookUrl = request.bookUrl,
                ))
            }
        } else {
            fail(request.chapterUrl, key, request.bookUrl)
        }
    }

    private fun fail(
        chapterUrl: String,
        key: String,
        bookUrl: String,
        error: MiruError? = null,
    ) {
        if (!isCurrent(chapterUrl, key)) return
        val failure = when (error) {
            is TranslationError.LocalNetworkRequired -> ChapterTranslationFailure.LocalNetworkRequired
            is TranslationError.CleartextNotAllowed -> ChapterTranslationFailure.CleartextNotAllowed
            else -> null
        }
        _state.update {
            it + (chapterUrl to ChapterTranslation(
                status = ChapterTranslationStatus.Failed,
                translations = emptyMap(),
                configKey = key,
                bookUrl = bookUrl,
                failure = failure,
            ))
        }
    }

    private fun isCurrent(chapterUrl: String, key: String): Boolean =
        _state.value[chapterUrl]?.configKey == key

    private fun epochOf(chapterUrl: String): Int =
        synchronized(queueLock) { epochs[chapterUrl] ?: 0 }

    private suspend fun <T> cacheRead(block: suspend () -> T?): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        // Cancellation is not a cache miss: the caller must stop, and the
        // worker must not translate a chapter whose load was cancelled.
        throw cancellation
    } catch (error: Exception) {
        MiruLog.w("translation", "chapter cache read failed ${error.javaClass.simpleName}")
        null
    }

    private suspend fun cacheWrite(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            MiruLog.w("translation", "chapter cache write failed ${error.javaClass.simpleName}")
        }
    }

    private fun remember(key: String, chapterUrl: String, translations: Map<Int, String>) {
        synchronized(queueLock) {
            memoryCache.remove(key)
            memoryCache[key] = translations
            memoryCacheChapter[key] = chapterUrl
            while (memoryCache.size > CACHE_LIMIT) {
                val oldest = memoryCache.keys.firstOrNull() ?: break
                memoryCache.remove(oldest)
                memoryCacheChapter.remove(oldest)
            }
        }
    }

    private fun queueKey(chapterUrl: String, key: String): String = "$chapterUrl\u0000$key"

    private companion object {
        const val CACHE_LIMIT = 12
        const val EPOCH_LIMIT = 128
    }
}

internal fun translationConfigKey(request: TranslationRequest): String = buildString {
    // The owning book is part of the identity: the same chapter URL under
    // another book must never satisfy this request.
    append(request.bookUrl)
    append('|')
    append(request.chapterUrl)
    append('|')
    append(request.provider.name)
    append('|')
    append(request.source?.value)
    append('|')
    append(request.target.value)
    append('|')
    append(request.prompt?.text)
    append('|')
    append(request.prompt?.version)
    append('|')
    append(request.revision.sourceHash)
    append('|')
    append(request.revision.pipelineVersion)
}

/**
 * The prompt actually sent for a chapter: the global one by default, or the
 * novel prompt either replacing it or appended to it.
 */
internal fun composePrompt(base: String, override: String, append: Boolean): PromptSpec? {
    val text = when {
        override.isBlank() -> base.trim()
        append && base.isNotBlank() -> base.trim() + "\n\n" + override.trim()
        else -> override.trim()
    }
    return text.takeIf { it.isNotBlank() }?.let { PromptSpec(text = it) }
}
