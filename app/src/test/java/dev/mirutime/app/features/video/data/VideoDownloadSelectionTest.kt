package dev.mirutime.app.features.video.data

import dev.mirutime.data.library.libraryBookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDownloadSelectionTest {
    private val raw = "https://yummy.test/video/1"
    private val route = libraryBookKey("yummyanime", raw)
    private val item = VideoDownloadItem(
        bookUrl = raw,
        chapterIndex = 7,
        chapterUrl = "https://cdn.test/7.mp4",
        state = VideoDownloadState.Downloading,
        progress = 0.42f,
    )

    @Test
    fun `a raw row is found by the route book key`() {
        val byIndex = videoDownloadsForBook(listOf(item), route)

        assertEquals(item, byIndex[7])
        assertEquals(0.42f, byIndex.getValue(7).progress)
    }

    @Test
    fun `another book is excluded`() {
        val other = libraryBookKey("other", "https://other.test/video")
        assertNull(videoDownloadsForBook(listOf(item), other)[7])
    }
}
