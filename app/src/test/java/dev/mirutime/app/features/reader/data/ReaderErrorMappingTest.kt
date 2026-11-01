package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.ChapterFailureKind
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.feature.reader.ReaderLoadFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The reader's content source turns the download-shaped typed failure into a
// plain-string ReaderLoadFailure: feature-reader cannot import :mirutime-core.
class ReaderErrorMappingTest {

    private val login = PluginError.UserMessage(
        title = "Login required",
        message = "Session expired",
        authUrl = "https://site.example/login?token=secret",
    )

    private class FailingProvider(private val failure: Throwable) : BookDataProvider {
        override suspend fun book(bookUrl: String): BookDetails? = null
        override suspend fun ensureHistoryBook(bookUrl: String) = Unit
        override suspend fun chapters(bookUrl: String): List<BookChapter> = emptyList()
        override suspend fun chapterContent(
            bookUrl: String,
            chapterIndex: Int,
        ): ChapterContent? = throw failure
        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean) =
            MembershipResult(success = true)
        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    @Test
    fun `typed plugin error becomes a plain reader failure`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FailingProvider(
                ChapterDownloadException(
                    kind = ChapterFailureKind.AUTH_REQUIRED,
                    retryable = false,
                    message = "Source login required",
                    pluginError = login,
                ),
            ),
        )

        try {
            repo.document("https://site.example/book/1", 1)
            fail("a typed content failure must not become a null document")
        } catch (failure: ReaderLoadFailure) {
            assertEquals("Login required", failure.title)
            assertTrue(failure.message, "Login required" in failure.message)
            assertTrue(failure.message, "op=content" in failure.message)
            assertEquals("https://site.example/login?token=secret", failure.authUrl)
        }
    }

    @Test
    fun `untyped content failure still carries a message`() = runTest {
        val repo = DefaultReaderRepository(
            provider = FailingProvider(
                ChapterDownloadException(
                    kind = ChapterFailureKind.NETWORK,
                    retryable = true,
                    message = "Network error while fetching the chapter",
                ),
            ),
        )

        try {
            repo.document("https://site.example/book/1", 1)
            fail("failure expected")
        } catch (failure: ReaderLoadFailure) {
            assertTrue(failure.message, "Network error while fetching the chapter" in failure.message)
        }
    }
}
