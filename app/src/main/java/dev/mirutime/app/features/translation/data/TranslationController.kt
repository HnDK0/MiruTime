package dev.mirutime.app.features.translation.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val pending = ArrayDeque<TranslationRequest>()
    private val queued = mutableSetOf<String>()
    // shortcut: one FIFO worker and a 12-entry cache; the translator already
    // retries, so no priorities, backoff or circuit breaker here.
    private val memoryCache = LinkedHashMap<String, Map<Int, String>>()
    private var worker: Job? = null

    /** Returns false when the chapter is already current or queued unchanged. */
    fun submit(request: TranslationRequest): Boolean {
        val key = configKey(request)
        val current = _state.value[request.chapterUrl]
        val alreadyCurrent = current != null &&
            current.configKey == key &&
            current.status != ChapterTranslationStatus.Failed
        if (alreadyCurrent) return false
        val queueKey = queueKey(request.chapterUrl, key)
        if (queueKey in queued) return false

        memoryCache[key]?.let { translations ->
            MiruLog.event(
                "translation",
                "chapter-cache",
                "hit" to true,
                "kind" to "memory",
                "chapter" to chapterHash(request.chapterUrl),
                "blocks" to translations.size,
            )
            _state.update {
                it + (request.chapterUrl to ChapterTranslation(
                    status = ChapterTranslationStatus.Ready,
                    translations = translations,
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
        queued += queueKey
        pending.addLast(request)
        startWorker()
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
     * Publishes an already cached chapter before the reader renders it: memory
     * first, then Room. Returns false on a miss without setting Loading or
     * starting the engine, so the caller keeps its normal submit fallback.
     */
    suspend fun preload(request: TranslationRequest): Boolean {
        val key = configKey(request)
        val current = _state.value[request.chapterUrl]
        if (current?.configKey == key) return current.status == ChapterTranslationStatus.Ready
        val fromMemory = memoryCache[key]
        val cached = fromMemory ?: try {
            cache?.load(request)
        } catch (cancellation: CancellationException) {
            // A cancelled preload must stay cancelled so the caller does not
            // publish a fallback after its scope is gone.
            throw cancellation
        } catch (error: Exception) {
            MiruLog.w("translation", "chapter preload failed ${error.javaClass.simpleName}")
            null
        }
        if (cached == null) return false
        if (fromMemory == null) remember(key, cached)
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
        pending.removeAll { it.chapterUrl in urls }
        queued.removeAll { key -> urls.any { key.startsWith("$it\u0000") } }
        memoryCache.keys.removeAll { key -> urls.any { key.startsWith("$it|") } }
    }

    private fun chapterHash(url: String): String = url.hashCode().toString(16)

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (pending.isNotEmpty()) {
                val request = pending.removeFirst()
                val key = configKey(request)
                try {
                    val disk = runCatching { cache?.load(request) }.getOrNull()
                    if (disk != null) {
                        MiruLog.event(
                            "translation",
                            "chapter-cache",
                            "hit" to true,
                            "kind" to "disk",
                            "chapter" to chapterHash(request.chapterUrl),
                            "blocks" to disk.size,
                        )
                        remember(key, disk)
                        _state.update {
                            it + (request.chapterUrl to ChapterTranslation(
                                status = ChapterTranslationStatus.Ready,
                                translations = disk,
                                configKey = key,
                                bookUrl = request.bookUrl,
                            ))
                        }
                        continue
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
                        // Bounded attempt: persist the failure, show Failed and
                        // let the FIFO worker move to the next chapter.
                        MiruLog.w(
                            "translation",
                            "chapter timeout chapterHash=${chapterHash(request.chapterUrl)} " +
                                "provider=${request.provider.name} " +
                                "timeoutMs=${chapterTimeout.inWholeMilliseconds}",
                        )
                        runCatching { cache?.markFailed(request) }
                        fail(request.chapterUrl, key, request.bookUrl)
                        continue
                    } catch (cancellation: CancellationException) {
                        // Real outer cancellation (screen closed, scope gone).
                        throw cancellation
                    } catch (error: Exception) {
                        MiruLog.e("translation", "chapter translation failed", error)
                        null
                    }
                    when (outcome) {
                        null -> {
                            MiruLog.event(
                                "translation",
                                "chapter-result",
                                "ok" to false,
                                "chapter" to chapterHash(request.chapterUrl),
                            )
                            runCatching { cache?.markFailed(request) }
                            fail(request.chapterUrl, key, request.bookUrl)
                        }
                        is Outcome.Ok -> {
                            val saved = runCatching { cache?.save(request, outcome.value) }
                            MiruLog.event(
                                "translation",
                                "chapter-result",
                                "ok" to true,
                                "chapter" to chapterHash(request.chapterUrl),
                                "blocks" to outcome.value.translations.size,
                                "dbSaved" to saved.isSuccess,
                            )
                            if (isCurrent(request.chapterUrl, key)) {
                                remember(key, outcome.value.translations)
                                _state.update {
                                    it + (request.chapterUrl to ChapterTranslation(
                                        status = ChapterTranslationStatus.Ready,
                                        translations = outcome.value.translations,
                                        configKey = key,
                                        bookUrl = request.bookUrl,
                                    ))
                                }
                            }
                        }
                        is Outcome.Err -> {
                            MiruLog.event(
                                "translation",
                                "chapter-result",
                                "ok" to false,
                                "chapter" to chapterHash(request.chapterUrl),
                            )
                            runCatching { cache?.markFailed(request) }
                            fail(request.chapterUrl, key, request.bookUrl, outcome.error)
                        }
                    }
                } finally {
                    queued -= queueKey(request.chapterUrl, key)
                }
            }
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

    private fun remember(key: String, translations: Map<Int, String>) {
        memoryCache.remove(key)
        memoryCache[key] = translations
        while (memoryCache.size > CACHE_LIMIT) {
            val oldest = memoryCache.keys.firstOrNull() ?: break
            memoryCache.remove(oldest)
        }
    }

    private fun configKey(request: TranslationRequest): String = buildString {
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

    private fun queueKey(chapterUrl: String, key: String): String = "$chapterUrl\u0000$key"

    private companion object {
        const val CACHE_LIMIT = 12
    }
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
