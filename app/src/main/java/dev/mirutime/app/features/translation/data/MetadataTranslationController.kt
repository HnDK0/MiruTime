package dev.mirutime.app.features.translation.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.translation.domain.MetadataRequest
import dev.mirutime.app.features.translation.domain.MetadataTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MetadataTranslationStatus { Loading, Ready, Failed }

/** Everything a FULL-mode metadata request needs, resolved once per book. */
data class MetadataConfig(
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec?,
)

/** Library cards translate only the title, before the book page is opened. */
data class TitleTranslationJob(
    val bookUrl: String,
    val title: String,
    val config: MetadataConfig,
)

/** Book page: title, synopsis and every chapter title in one batch. */
data class BookMetadataJob(
    val bookUrl: String,
    val title: String,
    val description: String,
    val chapters: List<Pair<Int, String>>,
    val config: MetadataConfig,
)

/**
 * Reader: book title plus chapter titles, without the synopsis. Keeping it a
 * separate kind means the reader never marks the book details as loaded, so
 * the book page can still translate its description afterwards.
 */
data class ReaderChaptersJob(
    val bookUrl: String,
    val title: String,
    val chapters: List<Pair<Int, String>>,
    val config: MetadataConfig,
)

data class BookMetadataTranslation(
    val config: MetadataConfig,
    val status: MetadataTranslationStatus,
    val title: String? = null,
    val description: String? = null,
    val chapterTitles: Map<Int, String> = emptyMap(),
    val detailsLoaded: Boolean = false,
    /** Provider that actually served the batch (free fallback may differ). */
    val provider: ProviderId? = null,
)

/**
 * Translates book metadata for FULL scope. Titles are batched per identical
 * config, so the library warms N cards with one request; opening a book sends
 * its synopsis and chapter titles in another batch. Results are keyed by
 * bookUrl and only shown while the config still matches.
 */
class MetadataTranslationController(
    private val engine: TranslationEngine,
    private val scope: CoroutineScope,
    private val store: MetadataTranslationStore? = null,
) {
    private val _state = MutableStateFlow<Map<String, BookMetadataTranslation>>(emptyMap())
    val state: StateFlow<Map<String, BookMetadataTranslation>> = _state.asStateFlow()

    private val pending = ArrayDeque<Pending>()
    private val queued = mutableSetOf<String>()
    // Insertion-ordered bookUrls for the small in-memory cache.
    private val order = LinkedHashSet<String>()
    private var worker: Job? = null

    fun submitTitles(jobs: List<TitleTranslationJob>) {
        val missing = jobs
            .filter { it.title.isNotBlank() }
            .filterNot { hasTitle(it.bookUrl, it.config) }
        if (missing.isEmpty()) return

        missing.groupBy { it.config }.forEach { (config, group) ->
            val units = group.mapIndexed { index, job ->
                TranslationUnit(id = index.toString(), text = job.title)
            }
            val request = MetadataRequest(
                bookId = group.first().bookUrl.hashCode().toLong(),
                key = "meta-titles:${config.cacheKey()}",
                units = units,
                source = config.source,
                target = config.target,
                provider = config.provider,
                prompt = config.prompt,
            )
            group.forEach { markLoading(it.bookUrl, config) }
            enqueue(
                Pending(
                    key = "titles:${config.cacheKey()}:" +
                        group.joinToString("|") { it.bookUrl },
                    books = group.map { it.bookUrl },
                    config = config,
                    request = request,
                    kind = MetadataKind.Titles,
                ),
            )
        }
    }

    fun submitBook(job: BookMetadataJob) {
        if (hasDetails(job.bookUrl, job.config)) return
        val units = buildList {
            job.title.takeIf { it.isNotBlank() }?.let { add(TranslationUnit("0", it)) }
            job.description.takeIf { it.isNotBlank() }?.let { add(TranslationUnit("1", it)) }
            job.chapters.forEach { (index, title) ->
                if (title.isNotBlank()) {
                    add(TranslationUnit((index + FIRST_CHAPTER_ID).toString(), title))
                }
            }
        }
        if (units.isEmpty()) return

        markLoading(job.bookUrl, job.config)
        enqueue(
            Pending(
                key = "book:${job.bookUrl}:${job.config.cacheKey()}",
                books = listOf(job.bookUrl),
                config = job.config,
                request = MetadataRequest(
                    bookId = job.bookUrl.hashCode().toLong(),
                    key = "meta:${job.bookUrl}:${job.config.cacheKey()}",
                    units = units,
                    source = job.config.source,
                    target = job.config.target,
                    provider = job.config.provider,
                    prompt = job.config.prompt,
                ),
                kind = MetadataKind.Details,
            ),
        )
    }

    fun submitChapters(job: ReaderChaptersJob) {
        if (hasChapters(job.bookUrl, job.config)) return
        val units = buildList {
            job.title.takeIf { it.isNotBlank() }?.let { add(TranslationUnit("0", it)) }
            job.chapters.forEach { (index, title) ->
                if (title.isNotBlank()) {
                    add(TranslationUnit((index + FIRST_CHAPTER_ID).toString(), title))
                }
            }
        }
        if (units.isEmpty()) return

        markLoading(job.bookUrl, job.config)
        enqueue(
            Pending(
                key = "chapters:${job.bookUrl}:${job.config.cacheKey()}",
                books = listOf(job.bookUrl),
                config = job.config,
                request = MetadataRequest(
                    bookId = job.bookUrl.hashCode().toLong(),
                    key = "meta-chapters:${job.bookUrl}:${job.config.cacheKey()}",
                    units = units,
                    source = job.config.source,
                    target = job.config.target,
                    provider = job.config.provider,
                    prompt = job.config.prompt,
                ),
                kind = MetadataKind.Chapters,
            ),
        )
    }

    private fun hasChapters(bookUrl: String, config: MetadataConfig): Boolean {
        val entry = _state.value[bookUrl]
        return entry?.config == config && entry.chapterTitles.isNotEmpty()
    }

    private fun hasTitle(bookUrl: String, config: MetadataConfig): Boolean {
        val entry = _state.value[bookUrl]
        return entry?.config == config && entry.title != null
    }

    private fun hasDetails(bookUrl: String, config: MetadataConfig): Boolean {
        val entry = _state.value[bookUrl]
        return entry?.config == config && entry.detailsLoaded
    }

    private fun markLoading(bookUrl: String, config: MetadataConfig) {
        _state.update { current ->
            val previous = current[bookUrl]?.takeIf { it.config == config }
            current + (bookUrl to BookMetadataTranslation(
                config = config,
                status = MetadataTranslationStatus.Loading,
                title = previous?.title,
                description = previous?.description,
                chapterTitles = previous?.chapterTitles.orEmpty(),
                detailsLoaded = previous?.detailsLoaded == true,
                provider = previous?.provider,
            ))
        }
        touch(bookUrl)
    }

    private fun enqueue(job: Pending) {
        if (!queued.add(job.key)) return
        pending.addLast(job)
        startWorker()
        MiruLog.event(
            "translation",
            "metadata-submit",
            "kind" to job.kind.name,
            "books" to job.books.size,
            "units" to job.request.units.size,
        )
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (pending.isNotEmpty()) {
                val job = pending.removeFirst()
                try {
                    val outcome = try {
                        engine.translateMetadata(job.request)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        MiruLog.e("translation", "metadata translation failed", error)
                        null
                    }
                    when (outcome) {
                        null -> {
                            MiruLog.event(
                                "translation",
                                "metadata-result",
                                "kind" to job.kind.name,
                                "ok" to false,
                            )
                            fail(job)
                        }
                        is Outcome.Ok -> {
                            MiruLog.event(
                                "translation",
                                "metadata-result",
                                "kind" to job.kind.name,
                                "ok" to true,
                                "books" to job.books.size,
                                "units" to outcome.value.translations.size,
                            )
                            apply(job, outcome.value)
                        }
                        is Outcome.Err -> {
                            MiruLog.event(
                                "translation",
                                "metadata-result",
                                "kind" to job.kind.name,
                                "ok" to false,
                            )
                            fail(job)
                        }
                    }
                } finally {
                    queued -= job.key
                }
            }
        }
    }

    private suspend fun apply(job: Pending, result: MetadataTranslationResult) {
        val translations = result.translations
        // One writer on the main dispatcher: build the next map in place and
        // publish it once, so a batch of 50 titles cannot be interleaved with
        // another state write and lose entries.
        var next = _state.value
        job.books.forEachIndexed { index, bookUrl ->
            val entry = next[bookUrl] ?: return@forEachIndexed
            if (entry.config != job.config) return@forEachIndexed
            next = when (job.kind) {
                MetadataKind.Titles -> {
                    val title = translations[index]?.takeIf { it.isNotBlank() }
                    next + (bookUrl to entry.copy(
                        status = MetadataTranslationStatus.Ready,
                        title = title ?: entry.title,
                        provider = result.provider,
                    ))
                }
                MetadataKind.Details -> {
                    val title = translations[0]?.takeIf { it.isNotBlank() }
                    val description = translations[1]?.takeIf { it.isNotBlank() }
                    val chapterTitles = chapterTitlesOf(translations)
                    next + (bookUrl to entry.copy(
                        status = MetadataTranslationStatus.Ready,
                        title = title ?: entry.title,
                        description = description ?: entry.description,
                        chapterTitles = chapterTitles.ifEmpty { entry.chapterTitles },
                        detailsLoaded = true,
                        provider = result.provider,
                    ))
                }
                MetadataKind.Chapters -> {
                    val title = translations[0]?.takeIf { it.isNotBlank() }
                    val chapterTitles = chapterTitlesOf(translations)
                    next + (bookUrl to entry.copy(
                        status = MetadataTranslationStatus.Ready,
                        title = title ?: entry.title,
                        chapterTitles = chapterTitles.ifEmpty { entry.chapterTitles },
                        provider = result.provider,
                    ))
                }
            }
        }
        _state.value = next
        job.books.forEach(::touch)
        persist(job, translations)
    }

    /**
     * Writes only the fields this batch produced. Runs after the UI state
     * update and never cancels the batch on failure: persistence is a cache.
     */
    private suspend fun persist(job: Pending, translations: Map<Int, String>) {
        val store = store ?: return
        val key = job.config.storeKey()
        var saved = 0
        job.books.forEachIndexed { index, bookUrl ->
            val patch = when (job.kind) {
                MetadataKind.Titles -> MetadataTranslationPatch(
                    title = translations[index]?.takeIf { it.isNotBlank() },
                )
                MetadataKind.Details -> MetadataTranslationPatch(
                    title = translations[0]?.takeIf { it.isNotBlank() },
                    description = translations[1]?.takeIf { it.isNotBlank() },
                    chapterTitles = chapterTitlesOf(translations),
                )
                MetadataKind.Chapters -> MetadataTranslationPatch(
                    title = translations[0]?.takeIf { it.isNotBlank() },
                    chapterTitles = chapterTitlesOf(translations),
                )
            }
            runCatching { store.save(bookUrl, key, patch) }
                .onSuccess { saved++ }
                .onFailure { MiruLog.e("translation", "metadata persist failed", it) }
        }
        MiruLog.event(
            "translation",
            "metadata-persisted",
            "kind" to job.kind.name,
            "books" to job.books.size,
            "saved" to saved,
        )
    }

    /** Loads a previously persisted book batch before asking the provider. */
    suspend fun hydrate(bookUrl: String, config: MetadataConfig) {
        val store = store ?: return
        val stored = runCatching { store.load(bookUrl, config.storeKey()) }
            .onFailure { MiruLog.e("translation", "metadata hydrate failed", it) }
            .getOrNull()
        MiruLog.event(
            "translation",
            "metadata-cache",
            "hit" to (stored != null),
            "kind" to "book",
            "book" to MiruLog.idHash(bookUrl),
        )
        if (stored == null) return
        mergeStored(bookUrl, config, stored)
    }

    /** Batch variant used by the library title lane: one repository query. */
    suspend fun hydrateTitles(jobs: List<TitleTranslationJob>) {
        val store = store ?: return
        val missing = jobs
            .filter { it.title.isNotBlank() }
            .filterNot { hasTitle(it.bookUrl, it.config) }
        if (missing.isEmpty()) return
        missing.groupBy { it.config }.forEach { (config, group) ->
            val stored = runCatching {
                store.loadTitles(group.map { it.bookUrl }, config.storeKey())
            }.onFailure { MiruLog.e("translation", "metadata title hydrate failed", it) }
                .getOrDefault(emptyMap())
            group.forEach { job ->
                stored[job.bookUrl]?.let { mergeStored(job.bookUrl, config, it) }
            }
            MiruLog.event(
                "translation",
                "metadata-cache",
                "hit" to stored.size,
                "kind" to "titles",
                "books" to group.size,
            )
        }
    }

    private fun mergeStored(
        bookUrl: String,
        config: MetadataConfig,
        stored: StoredMetadataTranslation,
    ) {
        if (stored.title == null && stored.description == null && stored.chapterTitles.isEmpty()) {
            return
        }
        _state.update { current ->
            val previous = current[bookUrl]?.takeIf { it.config == config }
            val merged = BookMetadataTranslation(
                config = config,
                status = previous?.status ?: MetadataTranslationStatus.Ready,
                title = previous?.title ?: stored.title,
                description = previous?.description ?: stored.description,
                chapterTitles = previous?.chapterTitles.orEmpty() + stored.chapterTitles,
                // Only a batch that requested the synopsis can mark details
                // done; a stored description is the durable proof of it.
                detailsLoaded = previous?.detailsLoaded == true || stored.description != null,
                provider = previous?.provider,
            )
            current + (bookUrl to merged)
        }
        touch(bookUrl)
    }

    private fun chapterTitlesOf(translations: Map<Int, String>): Map<Int, String> =
        translations.entries
            .mapNotNull { (id, text) ->
                if (id >= FIRST_CHAPTER_ID) id - FIRST_CHAPTER_ID to text else null
            }
            .toMap()

    private fun fail(job: Pending) {
        _state.update { current ->
            var next = current
            job.books.forEach { bookUrl ->
                val entry = next[bookUrl] ?: return@forEach
                if (entry.config != job.config) return@forEach
                next = next + (bookUrl to entry.copy(status = MetadataTranslationStatus.Failed))
            }
            next
        }
    }

    private fun touch(bookUrl: String) {
        order.remove(bookUrl)
        order.add(bookUrl)
        while (order.size > CACHE_LIMIT) {
            val oldest = order.first()
            if (_state.value[oldest]?.status == MetadataTranslationStatus.Loading) break
            order.remove(oldest)
            _state.update { it - oldest }
        }
    }

    private enum class MetadataKind { Titles, Details, Chapters }

    private data class Pending(
        val key: String,
        val books: List<String>,
        val config: MetadataConfig,
        val request: MetadataRequest,
        val kind: MetadataKind,
    )

    private companion object {
        const val FIRST_CHAPTER_ID = 2
        // A catalogue page is 50 books and the library reloads its own page on
        // every visit; the old 40-entry cap evicted the whole fresh batch right
        // after it was applied, so no card ever showed a translated title.
        const val CACHE_LIMIT = 160
    }
}
