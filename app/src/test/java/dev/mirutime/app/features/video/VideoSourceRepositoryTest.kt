package dev.mirutime.app.features.video

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.video.data.VideoSourceRepository
import dev.mirutime.app.features.video.domain.VideoLoadResult
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.reader.ChapterContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceRepositoryTest {
    @Test
    fun `load maps streams and never fetches chapter text`() = runTest {
        val provider = FakeVideoProvider(
            streams = Outcome.Ok(listOf(VideoStream("https://cdn/ep1.m3u8", quality = "720p"))),
        )
        val repo = VideoSourceRepository(
            provider = provider,
            library = null,
            now = { 1L },
        )
        // ensureHistoryBook is tracked through the fake's own flag.
        val result = repo.load("https://site/show/1", 1)
        val ready = result as VideoLoadResult.Ready
        assertEquals("Show", ready.source.bookTitle)
        assertEquals("Episode 1", ready.source.chapterTitle)
        assertEquals(2, ready.source.episodes.size)
        assertEquals("https://cdn/ep1.m3u8", ready.source.streams.single().url)
        assertEquals(0L, ready.source.startPositionMs)
        assertTrue(provider.historyOpened)
        assertFalse(provider.chapterTextCalled)
    }

    @Test
    fun `completed offline download skips the plugin resolver`() = runTest {
        val provider = FakeVideoProvider(Outcome.Err(PluginError.ScriptError("offline")))
        val stream = VideoStream(
            "https://cdn/ep1.m3u8",
            mime = "application/x-mpegURL",
            headers = mapOf("Referer" to "https://site/show/1"),
        )
        val repo = VideoSourceRepository(
            provider = provider,
            library = null,
            offlineStream = { _, _ -> stream },
        )

        val ready = repo.load("https://site/show/1", 1) as VideoLoadResult.Ready

        assertEquals("https://cdn/ep1.m3u8", ready.source.streams.single().url)
        assertEquals("application/x-mpegURL", ready.source.streams.single().mime)
        // Stored headers survive the offline path and the plugin resolver is
        // not called a second time.
        assertEquals("https://site/show/1", ready.source.streams.single().headers["Referer"])
        assertEquals(0, provider.videoStreamCalls)
    }

    @Test
    fun `empty stream list is an empty state, not an error`() = runTest {
        val repo = VideoSourceRepository(FakeVideoProvider(Outcome.Ok(emptyList())), null)
        assertEquals(VideoLoadResult.Empty, repo.load("https://site/show/1", 1))
    }

    @Test
    fun `plugin script failure keeps its message`() = runTest {
        val repo = VideoSourceRepository(
            FakeVideoProvider(Outcome.Err(PluginError.ScriptError("resolver exploded"))),
            null,
        )
        assertEquals(
            VideoLoadResult.Error("resolver exploded"),
            repo.load("https://site/show/1", 1),
        )
    }

    @Test
    fun `headers merge plugin defaults and stream values`() = runTest {
        val repo = VideoSourceRepository(
            provider = FakeVideoProvider(Outcome.Ok(emptyList())),
            library = null,
            pluginHeaders = { mapOf("Referer" to "https://site/ep", "Cookie" to "a=1") },
        )
        val headers = repo.headersFor(
            VideoStream("https://cdn/ep.m3u8", headers = mapOf("User-Agent" to "plugin")),
        )
        assertEquals("https://site/ep", headers["Referer"])
        assertEquals("a=1", headers["Cookie"])
        assertEquals("plugin", headers["User-Agent"])
    }

    private class FakeVideoProvider(
        private val streams: Outcome<List<VideoStream>>,
    ) : BookDataProvider {
        var historyOpened = false
        var chapterTextCalled = false
        var videoStreamCalls = 0

        override suspend fun book(bookUrl: String): BookDetails? = testDetails(bookUrl)

        override suspend fun ensureHistoryBook(bookUrl: String) {
            historyOpened = true
        }

        override suspend fun chapters(bookUrl: String): List<BookChapter> = testChapters()

        override suspend fun chapterContent(
            bookUrl: String,
            chapterIndex: Int,
        ): ChapterContent? {
            chapterTextCalled = true
            error("video must not fetch chapter text")
        }

        override suspend fun videoStreams(
            bookUrl: String,
            chapterIndex: Int,
        ): Outcome<List<VideoStream>> {
            videoStreamCalls++
            return streams
        }

        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit

        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
            MembershipResult(success = true)

        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) =
            Unit

        override suspend fun downloadNext100(bookUrl: String) = Unit

        override suspend fun markChapterRead(
            bookUrl: String,
            chapterIndex: Int,
            read: Boolean,
        ) = Unit
    }
}

private fun testChapters(): List<BookChapter> = listOf(
    BookChapter(
        index = 1,
        title = "Episode 1",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    ),
    BookChapter(
        index = 2,
        title = "Episode 2",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    ),
)

private fun testDetails(bookUrl: String): BookDetails = BookDetails(
    bookUrl = bookUrl,
    title = "Show",
    source = "test",
    author = "Author",
    originLanguage = null,
    contentType = ContentType.Video,
    rating = 0f,
    ongoing = true,
    updatedLabel = "",
    totalChapters = testChapters().size,
    readCount = 0,
    readMinutes = 0,
    ttsMinutes = 0,
    inLibrary = false,
    shelfId = "reading",
    genres = emptyList(),
    description = "",
    sourceUrl = bookUrl,
    authUrl = null,
    chapters = testChapters(),
    reviews = emptyList(),
)
