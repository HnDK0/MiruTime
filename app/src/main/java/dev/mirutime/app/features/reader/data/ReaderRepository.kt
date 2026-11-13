package dev.mirutime.app.features.reader.data

import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.ChapterFailureKind
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.feature.reader.api.ReaderContentSource
import dev.mirutime.feature.reader.api.ReaderTextRules
import dev.mirutime.feature.reader.ReaderLoadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ReaderRepository : ReaderContentSource

// Default implementation: all book/chapter data is delegated to the injected
// provider; the name has no fake behaviour.
class DefaultReaderRepository(
    private val provider: BookDataProvider,
    private val library: LibraryRepository? = null,
    private val textRules: ReaderTextRules = AppReaderTextRules(),
    // Reading paths only: bulk translation and rules reload must keep their
    // real-source semantics.
    private val translationOnlyDocument: suspend (bookUrl: String, chapterIndex: Int) -> ReaderDocument? =
        { _, _ -> null },
    private val onDocumentLoaded: suspend (bookUrl: String, chapterIndex: Int) -> Unit =
        { bookUrl, chapterIndex ->
            touchHistoryBestEffort(library, bookUrl, chapterIndex)
        },
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
            isVideo = book.contentType == ContentType.Video ||
                book.contentType == ContentType.Audio,
            coverUrl = book.coverUrl,
        )
    }

    override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? =
        loadDocument(bookUrl, chapterIndex, allowTranslationOnly = true)?.also {
            onDocumentLoaded(bookUrl, chapterIndex)
        }

    override suspend fun documentForTranslation(
        bookUrl: String,
        chapterIndex: Int,
    ): ReaderDocument? =
        loadDocument(bookUrl, chapterIndex, allowTranslationOnly = false)

    override suspend fun documentForPrefetch(
        bookUrl: String,
        chapterIndex: Int,
    ): ReaderDocument? =
        loadDocument(bookUrl, chapterIndex, allowTranslationOnly = true)

    override suspend fun documentForRulesReload(
        bookUrl: String,
        chapterIndex: Int,
        allowTranslationOnly: Boolean,
    ): ReaderDocument? =
        loadDocument(bookUrl, chapterIndex, allowTranslationOnly)

    private suspend fun loadDocument(
        bookUrl: String,
        chapterIndex: Int,
        allowTranslationOnly: Boolean,
    ): ReaderDocument? {
        val content = try {
            withContext(Dispatchers.IO) {
                MiruLog.d(
                    "reader",
                    "event=reader-thread role=provider thread=${Thread.currentThread().name}",
                )
                provider.chapterContent(bookUrl, chapterIndex)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ChapterDownloadException) {
            // feature-reader cannot import :mirutime-core: translate the typed
            // plugin failure into plain strings at this boundary.
            val report = buildPluginErrorReport(
                error = failure.pluginError,
                sourceId = PluginBookIndex.sourceForBook(bookUrl),
                operation = "content",
                fallbackMessage = failure.message,
                identity = bookUrl,
            )
            // A login/clearance wall is actionable; cached text must not hide
            // it. HTTP 401/403 and Cloudflare carry no authUrl, so the typed
            // kind is the only reliable signal.
            val actionable = report.authUrl != null ||
                failure.kind == ChapterFailureKind.AUTH_REQUIRED ||
                failure.kind == ChapterFailureKind.CLOUDFLARE
            if (allowTranslationOnly && !actionable) {
                translationOnlyDocument(bookUrl, chapterIndex)?.let { return it }
            }
            throw ReaderLoadFailure(
                title = report.title.orEmpty(),
                message = report.detailText,
                authUrl = report.authUrl,
                // A login page or a missing chapter never heals by retrying.
                retryable = failure.retryable && report.authUrl == null,
            )
        } ?: run {
            return if (allowTranslationOnly) {
                translationOnlyDocument(bookUrl, chapterIndex)
            } else {
                null
            }
        }
        val chapterUrl = content.chapterUrl ?: chapterKey(bookUrl, chapterIndex)
        // User cleanup rules are part of the text pipeline, not a preview:
        // apply the effective global + per-novel set to every block (and the
        // title) so the reader, translation planner and EPUB all see the same
        // cleaned source.
        val rules = textRules.effectiveRules(bookUrl)
        // HTML parsing and sentence splitting are CPU-bound work over the whole
        // chapter. The reader session scope is Main.immediate, so without this
        // every chapter change janked the frame it was triggered from.
        val title = withContext(Dispatchers.Default) {
            applyRulesFor(rules, content.title, RegexRuleTarget.Original)
        }
        val blocks = withContext(Dispatchers.Default) {
            // Rules are applied inside the parser, before sentence spans are
            // computed, so rules that shorten text never leave stale offsets.
            ReaderHtmlParser.parseDocument(content.html, chapterUrl) { text ->
                applyRulesFor(rules, text, RegexRuleTarget.Original)
            }
        }
        val document = ReaderDocument(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            title = title,
            blocks = blocks,
        )
        return document
    }

    override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
        provider.markChapterRead(bookUrl, chapterIndex, read)
    }
}

/**
 * Reading history is a side effect, not a prerequisite: a failed touch must not
 * turn a successfully loaded chapter into a reader failure.
 */
internal suspend fun touchHistoryBestEffort(
    library: LibraryRepository?,
    bookUrl: String,
    chapterIndex: Int,
) {
    try {
        library?.touchHistory(bookUrl, chapterIndex, System.currentTimeMillis())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MiruLog.w("reader", "history touch failed ${failure.javaClass.simpleName}")
    }
}
