package dev.mirutime.app.features.reader.data

import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.core.text.applyRules
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.feature.reader.api.ReaderContentSource
import dev.mirutime.feature.reader.api.ReaderTextRules
import dev.mirutime.feature.reader.ReaderLoadFailure

interface ReaderRepository : ReaderContentSource

// Default implementation: all book/chapter data is delegated to the injected
// provider; the name has no fake behaviour.
class DefaultReaderRepository(
    private val provider: BookDataProvider,
    private val library: LibraryRepository? = null,
    private val textRules: ReaderTextRules = AppReaderTextRules(),
) : ReaderRepository {
    override suspend fun book(bookUrl: String): ReaderBook? {
        // Opening the reader is the only page-open path that may create a
        // history-only row. The book page itself stays read-only.
        provider.ensureHistoryBook(bookUrl)
        val book = provider.book(bookUrl) ?: return null
        // The reader needs the whole list; plugin metadata arrives without it.
        val chapters = provider.chapters(bookUrl)
        return ReaderBook(
            bookUrl = book.bookUrl,
            title = book.title,
            chapters = chapters.map { chapter ->
                ReaderChapter(
                    chapterUrl = chapter.url ?: chapterKey(book.bookUrl, chapter.index),
                    index = chapter.index,
                    title = chapter.title,
                    isRead = chapter.isRead,
                )
            },
            isManga = book.contentType == ContentType.Manga,
            isVideo = book.contentType == ContentType.Video,
            coverUrl = book.coverUrl,
        )
    }

    override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? =
        loadDocument(bookUrl, chapterIndex, touchHistory = true)

    override suspend fun documentForTranslation(
        bookUrl: String,
        chapterIndex: Int,
    ): ReaderDocument? = loadDocument(bookUrl, chapterIndex, touchHistory = false)

    override suspend fun documentForPrefetch(
        bookUrl: String,
        chapterIndex: Int,
    ): ReaderDocument? = loadDocument(bookUrl, chapterIndex, touchHistory = false)

    private suspend fun loadDocument(
        bookUrl: String,
        chapterIndex: Int,
        touchHistory: Boolean,
    ): ReaderDocument? {
        val content = try {
            provider.chapterContent(bookUrl, chapterIndex)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ChapterDownloadException) {
            // feature-reader cannot import :mirutime-core: translate the typed
            // plugin failure into plain strings at this boundary.
            val report = buildPluginErrorReport(
                error = failure.pluginError,
                sourceId = PluginBookIndex.sourceFor(bookUrl),
                operation = "content",
                fallbackMessage = failure.message,
                identity = bookUrl,
            )
            throw ReaderLoadFailure(
                title = report.title.orEmpty(),
                message = report.detailText,
                authUrl = report.authUrl,
            )
        } ?: return null
        val chapterUrl = content.chapterUrl ?: chapterKey(bookUrl, chapterIndex)
        // User cleanup rules are part of the text pipeline, not a preview:
        // apply the effective global + per-novel set to every block (and the
        // title) so the reader, translation planner and EPUB all see the same
        // cleaned source.
        val rules = textRules.effectiveRules(bookUrl)
        val document = ReaderDocument(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            title = applyRules(rules, content.title),
            // Rules are applied inside the parser, before sentence spans are
            // computed, so rules that shorten text never leave stale offsets.
            blocks = ReaderHtmlParser.parseDocument(content.html, chapterUrl) { text ->
                applyRules(rules, text)
            },
        )
        if (touchHistory) library?.touchHistory(bookUrl, chapterIndex, System.currentTimeMillis())
        return document
    }

    override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
        provider.markChapterRead(bookUrl, chapterIndex, read)
    }
}
