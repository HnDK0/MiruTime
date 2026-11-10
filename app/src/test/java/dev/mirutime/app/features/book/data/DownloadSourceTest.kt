package dev.mirutime.app.features.book.data

import dev.mirutime.data.library.libraryBookKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceTest {

    @Test
    fun `persisted install id wins over a warm index`() {
        assertEquals(
            "plugin.a",
            resolveDownloadSource("https://x.test/book", "plugin.a") { "plugin.b" },
        )
    }

    @Test
    fun `indexed source is the second choice`() {
        assertEquals(
            "plugin.b",
            resolveDownloadSource("https://x.test/book", null) { "plugin.b" },
        )
    }

    @Test
    fun `book key still resolves when the index is cold`() {
        val key = libraryBookKey("plugin.c", "https://x.test/book")
        assertEquals("plugin.c", resolveDownloadSource(key, null) { null })
    }

    @Test
    fun `blank values are ignored`() {
        assertNull(resolveDownloadSource("https://x.test/book", " ") { "" })
    }

    @Test
    fun `the provider uses the persisted install id before consulting the index`() = runTest {
        PluginBookIndex.clear()
        var lookedUp: String? = null
        val provider = PluginBookDataProvider(
            graphOrNull = null,
            library = null,
            pluginSourceFor = { id ->
                lookedUp = id
                null
            },
        )

        val persistedFailure = runCatching {
            provider.downloadChapterText(URL, 1, "persisted-a")
        }.exceptionOrNull()

        assertTrue(persistedFailure is ChapterDownloadException)
        assertEquals(ChapterFailureKind.SOURCE, (persistedFailure as ChapterDownloadException).kind)
        assertEquals("Source unavailable", persistedFailure.message)
        assertEquals("persisted-a", lookedUp)

        lookedUp = null
        val coldFailure = runCatching {
            provider.downloadChapterText(URL, 1, null)
        }.exceptionOrNull()

        assertTrue(coldFailure is ChapterDownloadException)
        assertEquals("Unknown source", (coldFailure as ChapterDownloadException).message)
        assertNull(lookedUp)
    }

    private companion object {
        const val URL = "https://x.test/book"
    }
}
