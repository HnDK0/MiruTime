package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.core.reader.ChapterSource
import dev.mirutime.data.library.ChapterDownloadState
import dev.mirutime.data.library.ChapterStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterStatusOverlayTest {

    private val url = "https://site/book"

    private fun chapter(index: Int, url: String? = "https://site/ch/$index") = BookChapter(
        index = index,
        title = "Chapter $index",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
        url = url,
    )

    private fun snapshot(
        read: Set<Int> = emptySet(),
        downloaded: Set<Int> = emptySet(),
        translated: Set<Int> = emptySet(),
        last: Int = 3,
        lastReadIndex: Int? = null,
        lastReadUrl: String? = null,
        downloads: Map<Int, ChapterDownloadState> = emptyMap(),
        sizes: Map<Int, Long> = emptyMap(),
    ) = ChapterStatusSnapshot(
        bookId = 7,
        chapterCount = 3,
        readIndexes = read,
        downloadedIndexes = downloaded,
        downloadedUrls = downloaded.mapTo(HashSet()) { "https://site/ch/$it" },
        translatedIndexes = translated,
        translatedUrls = translated.mapTo(HashSet()) { "https://site/ch/$it" },
        lastIndex = last,
        lastChapterId = 30,
        lastReadIndex = lastReadIndex,
        lastReadUrl = lastReadUrl,
        downloads = downloads,
        sizeBytes = sizes,
    )

    @Test
    fun `overlay maps every persisted status and keeps last-read separate`() {
        val chapters = (1..3).map { chapter(it) }

        val mapped = applyChapterStatus(
            chapters = chapters,
            snapshot = snapshot(
                read = setOf(1),
                downloaded = setOf(2),
                translated = setOf(3),
                lastReadIndex = 2,
                lastReadUrl = "https://site/ch/2",
            ),
            offline = false,
        )

        assertTrue(mapped[0].isRead)
        assertTrue(mapped[1].downloaded)
        assertTrue(mapped[2].translated)
        assertTrue(mapped[2].isLast)
        assertFalse(mapped[0].isLast)
        assertTrue(mapped[1].isLastRead)
        assertFalse(mapped[2].offline)
    }

    @Test
    fun `offline local ignores cloud downloaded but keeps translation and last`() {
        val mapped = applyChapterStatus(
            chapters = (1..3).map { chapter(it) },
            snapshot = snapshot(
                downloaded = setOf(1, 2, 3),
                translated = setOf(2),
            ),
            offline = true,
        )

        assertTrue(mapped.all { it.offline })
        assertTrue(mapped.none { it.downloaded })
        assertTrue(mapped[1].translated)
        assertTrue(mapped[2].isLast)
    }

    @Test
    fun `persisted body size overlays the chapter and unknown stays zero`() {
        val mapped = applyChapterStatus(
            chapters = (1..3).map { chapter(it) },
            snapshot = snapshot(sizes = mapOf(1 to 500L)),
            offline = false,
        )

        assertEquals(500L, mapped[0].sizeBytes)
        assertEquals(0L, mapped[1].sizeBytes)
    }

    @Test
    fun `completed download clears queued state`() {
        val mapped = applyChapterStatus(
            chapters = listOf(chapter(1)),
            snapshot = snapshot(downloads = mapOf(1 to ChapterDownloadState("COMPLETED", 1.0))),
            offline = false,
        )

        assertNull(mapped[0].downloadState)
        assertFalse(mapped[0].downloading)
    }

    @Test
    fun `queued download keeps queued state until completion`() {
        val mapped = applyChapterStatus(
            chapters = listOf(chapter(1)),
            snapshot = snapshot(downloads = mapOf(1 to ChapterDownloadState("QUEUED", 0.0))),
            offline = false,
        )

        assertEquals("QUEUED", mapped[0].downloadState)
        assertTrue(mapped[0].downloading)
    }

    @Test
    fun `missing snapshot still derives the last chapter from the list`() {
        val mapped = applyChapterStatus((1..4).map { chapter(it) }, snapshot = null, offline = false)

        assertTrue(mapped[3].isLast)
        assertTrue(mapped.none { it.isRead || it.downloaded || it.translated || it.isLastRead })
    }

    @Test
    fun `source selection distinguishes SAF direct cache imported and network`() {
        assertEquals(
            ChapterSource.LOCAL_SAF_DIRECT,
            chapterSourceFor("local-file://content%3A%2F%2Ftree%2Fdoc", safCacheHit = false),
        )
        assertEquals(
            ChapterSource.LOCAL_SAF_CACHE,
            chapterSourceFor("local-file://content%3A%2F%2Ftree%2Fdoc", safCacheHit = true),
        )
        assertEquals(
            ChapterSource.IMPORTED_DB_BODY,
            chapterSourceFor("local://uuid", safCacheHit = false),
        )
        assertEquals(
            ChapterSource.PLUGIN_NETWORK,
            chapterSourceFor(url, safCacheHit = false),
        )
    }

    @Test
    fun `source log line carries only metadata`() {
        val line = chapterSourceLogLine(
            source = ChapterSource.PLUGIN_NETWORK,
            chapterId = 12,
            chapterIndex = 3,
            bytes = 900,
            elapsedMs = 45,
            cacheHit = false,
        )

        assertEquals(
            "source=PLUGIN_NETWORK chapterId=12 chapterIndex=3 bytes=900 elapsedMs=45 cache=miss",
            line,
        )
        assertFalse(line.contains("http"))
    }

    @Test
    fun `active queue state and progress overlay the chapter row`() {
        val mapped = applyChapterStatus(
            chapters = (1..3).map { chapter(it) },
            snapshot = snapshot(
                downloads = mapOf(
                    2 to ChapterDownloadState("RUNNING", 0.4),
                    3 to ChapterDownloadState("FAILED", 1.0),
                ),
            ),
            offline = false,
        )

        assertTrue(mapped[1].downloading)
        assertEquals("RUNNING", mapped[1].downloadState)
        assertEquals(0.4f, mapped[1].downloadProgress, 0.0001f)
        assertFalse(mapped[2].downloading)
        assertEquals("FAILED", mapped[2].downloadState)
    }
}
