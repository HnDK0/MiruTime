package dev.mirutime.app.features.book.data

import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.SubtitleTrack
import dev.mirutime.core.api.VideoStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBookDataProviderVideoTest {

    private val bookUrl = "https://video.test/book/1"
    private val sourceId = "video-source"

    private class FakeSource(
        private val content: ChapterContent,
    ) : PluginSource {
        var receivedHtml: String? = null
        var receivedUrl: String? = null

        override val metadata = PluginMetadata(
            id = "video-source",
            name = "Video",
            baseUrl = "https://video.test/",
            contentType = ContentType.VIDEO,
            language = "en",
            api = 1,
            version = "1",
            sha256 = "sha",
        )

        override suspend fun catalog(index: Int): Outcome<CatalogPage> = error("unused")

        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
            error("unused")

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = error("unused")

        override suspend fun book(url: String): Outcome<BookMeta> = error("unused")

        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            Outcome.Ok(listOf(Chapter("One", "https://video.test/ch/1")))

        override suspend fun chapterContent(
            html: String,
            url: String,
        ): Outcome<ChapterContent> {
            receivedHtml = html
            receivedUrl = url
            return Outcome.Ok(content)
        }

        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            error("unused")
    }

    private fun provider(source: PluginSource) = PluginBookDataProvider(
        // No PluginGraph: a chapterContent path that fetched HTML would throw.
        pluginSourceFor = { if (it == sourceId) source else null },
    )

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    @Test
    fun `video streams are parsed from empty html and keep every field`() = runTest {
        PluginBookIndex.put(bookUrl, sourceId)
        val stream = VideoStream(
            url = "https://cdn.test/video.m3u8",
            headers = mapOf("Referer" to "https://video.test/"),
            mime = "application/x-mpegURL",
            quality = "1080p",
            subtitles = listOf(SubtitleTrack("https://cdn.test/ru.vtt", "ru", "Russian")),
        )
        val source = FakeSource(ChapterContent.Video(listOf(stream)))

        val result = provider(source).videoStreams(bookUrl, 1)

        assertTrue(result is Outcome.Ok)
        assertEquals(listOf(stream), (result as Outcome.Ok).value)
        assertEquals("", source.receivedHtml)
        assertEquals("https://video.test/ch/1", source.receivedUrl)
    }

    @Test
    fun `empty video list is a successful empty result`() = runTest {
        PluginBookIndex.put(bookUrl, sourceId)

        val result = provider(FakeSource(ChapterContent.Video(emptyList())))
            .videoStreams(bookUrl, 1)

        assertTrue(result is Outcome.Ok)
        assertTrue((result as Outcome.Ok).value.isEmpty())
    }

    @Test
    fun `text and page chapters are typed errors`() = runTest {
        PluginBookIndex.put(bookUrl, sourceId)

        val text = provider(FakeSource(ChapterContent.Text("<p>text</p>")))
            .videoStreams(bookUrl, 1)
        val pages = provider(FakeSource(ChapterContent.Pages(listOf("p1.jpg"))))
            .videoStreams(bookUrl, 1)

        assertTrue(text is Outcome.Err)
        assertTrue(pages is Outcome.Err)
    }
}
