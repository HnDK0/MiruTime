package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.settings.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one Translate-selected pass queued, skipped or could not load. */
data class BulkTranslationResult(
    val configured: Boolean,
    val enqueued: Int,
    val alreadyCurrent: Int,
    val skipped: Int,
    val unloaded: Set<Int>,
)

/**
 * Book-list bulk translation. It ensures the history row once, parses each
 * selected chapter without touching reading progress, and submits one request
 * per chapter to the shared [TranslationController] (single FIFO worker, so a
 * large selection never floods the network).
 */
class ChapterBulkTranslator(
    private val provider: BookDataProvider,
    private val controller: TranslationController,
    private val loadDocument: suspend (bookUrl: String, chapterIndex: Int) -> ReaderDocument?,
    private val configFor: (bookUrl: String) -> ChapterTranslationConfig? =
        { ChapterTranslationPlanner.config(it, activeTranslationMode(it)) },
) {
    suspend fun translate(
        bookUrl: String,
        chapterIndexes: Collection<Int>,
    ): BulkTranslationResult {
        val config = configFor(bookUrl)
            ?: return BulkTranslationResult(
                configured = false,
                enqueued = 0,
                alreadyCurrent = 0,
                skipped = 0,
                unloaded = emptySet(),
            )
        val indexes = chapterIndexes.filter { it > 0 }.distinct().sorted()
        if (indexes.isEmpty()) {
            return BulkTranslationResult(true, 0, 0, 0, emptySet())
        }
        // Direct SAF chapters only have ids after this call; imported/plugin
        // rows become durable too, so RoomTranslationCache can attach by id.
        withContext(Dispatchers.IO) { provider.ensureHistoryBook(bookUrl) }

        var enqueued = 0
        var alreadyCurrent = 0
        var skipped = 0
        val unloaded = LinkedHashSet<Int>()
        for (index in indexes) {
            val document = withContext(Dispatchers.IO) { loadDocument(bookUrl, index) }
            if (document == null) {
                unloaded += index
                continue
            }
            val request = ChapterTranslationPlanner.request(document, config)
            if (request == null) {
                skipped++
                continue
            }
            if (controller.submit(request)) enqueued++ else alreadyCurrent++
        }
        return BulkTranslationResult(
            configured = true,
            enqueued = enqueued,
            alreadyCurrent = alreadyCurrent,
            skipped = skipped,
            unloaded = unloaded,
        )
    }
}

/** The same mode resolution the reader uses; kept here for the bulk caller. */
fun activeTranslationMode(bookUrl: String): TranslationMode? = translationMode(
    novel = AppSettings.novelTranslation[bookUrl],
    plugin = AppSettings.pluginTranslation[translationSourceId(bookUrl)],
    globalEnabled = AppSettings.translationGlobalMode,
    globalSource = AppSettings.globalSource,
    globalTarget = AppSettings.globalTarget,
    globalProvider = AppSettings.provider,
)
