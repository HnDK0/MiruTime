package dev.mirutime.app.features.video

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import dev.mirutime.app.features.video.data.ChapterDownloadRow
import dev.mirutime.app.features.video.data.VideoDownloadAction
import dev.mirutime.app.features.video.data.VideoDownloadData
import dev.mirutime.app.features.video.data.VideoDownloadItem
import dev.mirutime.app.features.video.data.VideoDownloadMeta
import dev.mirutime.app.features.video.data.VideoDownloadState
import dev.mirutime.app.features.video.data.VideoDownloaderKind
import dev.mirutime.app.features.video.data.hasConflictingDownload
import dev.mirutime.app.features.video.data.isCancellableVideoState
import dev.mirutime.app.features.video.data.isPausableVideoState
import dev.mirutime.app.features.video.data.mergeDownloadItems
import dev.mirutime.app.features.video.data.replaceableChapterDownloadIds
import dev.mirutime.app.features.video.data.sameBookIdentity
import dev.mirutime.app.features.video.data.videoDownloadActions
import dev.mirutime.app.features.video.data.videoDownloadId
import dev.mirutime.app.features.video.data.videoDownloadState
import dev.mirutime.app.features.video.data.videoDownloaderKind
import dev.mirutime.data.library.libraryBookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class VideoDownloadManagerTest {

    @Test
    fun `request data round-trips book chapter and headers`() {
        val headers = mapOf(
            "Referer" to "https://example.com/watch?a=1&b=2",
            "User-Agent" to "MiruTest/1.0",
            "X-Empty" to "",
        )

        val decoded = VideoDownloadData.decode(
            VideoDownloadData.encode("https://example.com/book/1", 7, headers),
        )

        assertEquals(
            VideoDownloadMeta("https://example.com/book/1", 7, headers),
            decoded,
        )
    }

    @Test
    fun `request data rejects corrupt payloads`() {
        assertNull(VideoDownloadData.decode(null))
        assertNull(VideoDownloadData.decode(ByteArray(0)))
        assertNull(VideoDownloadData.decode("v2\nx\n1".toByteArray()))
        assertNull(VideoDownloadData.decode("v1\nhttps%3A%2F%2Fx\nnot-a-number".toByteArray()))
        assertNull(VideoDownloadData.decode("v1\nhttps%3A%2F%2Fx\n1\nbroken".toByteArray()))
    }

    @Test
    fun `downloader kind follows mime and url fallback`() {
        assertEquals(
            VideoDownloaderKind.Hls,
            videoDownloaderKind("application/x-mpegURL", "https://cdn/x"),
        )
        assertEquals(
            VideoDownloaderKind.Hls,
            videoDownloaderKind(null, "https://cdn/live/index.m3u8?token=1"),
        )
        assertEquals(
            VideoDownloaderKind.Dash,
            videoDownloaderKind("application/dash+xml", "https://cdn/manifest"),
        )
        assertEquals(
            VideoDownloaderKind.Dash,
            videoDownloaderKind(null, "https://cdn/manifest.mpd"),
        )
        assertEquals(
            VideoDownloaderKind.Progressive,
            videoDownloaderKind("video/mp4", "https://cdn/episode.mp4"),
        )
        assertEquals(
            VideoDownloaderKind.Progressive,
            videoDownloaderKind(null, "https://cdn/episode"),
        )
    }

    @Test
    fun `media3 states map to the screen model`() {
        // Values are androidx.media3.exoplayer.offline.Download.STATE_*.
        assertEquals(VideoDownloadState.Queued, videoDownloadState(0))
        assertEquals(VideoDownloadState.Downloading, videoDownloadState(2))
        assertEquals(VideoDownloadState.Paused, videoDownloadState(1))
        assertEquals(VideoDownloadState.Completed, videoDownloadState(3))
        assertEquals(VideoDownloadState.Failed, videoDownloadState(4))
        assertEquals(VideoDownloadState.Failed, videoDownloadState(5))
        assertEquals(VideoDownloadState.Downloading, videoDownloadState(7))
    }

    @Test
    fun `cancel selects active downloads only`() {
        // 0 QUEUED, 1 STOPPED, 2 DOWNLOADING, 3 COMPLETED, 4 FAILED,
        // 5 REMOVING, 7 RESTARTING.
        assertTrue(isCancellableVideoState(0))
        assertTrue(isCancellableVideoState(1))
        assertTrue(isCancellableVideoState(2))
        assertTrue(isCancellableVideoState(5))
        assertTrue(isCancellableVideoState(7))
        assertEquals(false, isCancellableVideoState(3))
        assertEquals(false, isCancellableVideoState(4))
    }

    @Test
    fun `pause skips completed failed and already stopped`() {
        assertTrue(isPausableVideoState(0))
        assertTrue(isPausableVideoState(2))
        assertTrue(isPausableVideoState(7))
        assertEquals(false, isPausableVideoState(1))
        assertEquals(false, isPausableVideoState(3))
        assertEquals(false, isPausableVideoState(4))
        assertEquals(false, isPausableVideoState(5))
    }

    @Test
    fun `row controls have no dead actions`() {
        assertEquals(
            listOf(VideoDownloadAction.Pause, VideoDownloadAction.Cancel),
            videoDownloadActions(VideoDownloadState.Queued),
        )
        assertEquals(
            listOf(VideoDownloadAction.Pause, VideoDownloadAction.Cancel),
            videoDownloadActions(VideoDownloadState.Downloading),
        )
        assertEquals(
            listOf(VideoDownloadAction.Resume, VideoDownloadAction.Cancel),
            videoDownloadActions(VideoDownloadState.Paused),
        )
        assertTrue(videoDownloadActions(VideoDownloadState.Completed).isEmpty())
        assertTrue(videoDownloadActions(VideoDownloadState.Failed).isEmpty())
        assertTrue(videoDownloadActions(null).isEmpty())
    }

    @Test
    fun `book identity keeps two installs apart and accepts a legacy raw url`() {
        val first = libraryBookKey("plugin-a", "https://a/book")
        val second = libraryBookKey("plugin-b", "https://a/book")

        assertTrue(sameBookIdentity(first, first))
        assertFalse(sameBookIdentity(first, second))
        // Rows written before route keys carry the raw URL.
        assertTrue(sameBookIdentity(first, "https://a/book"))
        assertTrue(sameBookIdentity("https://a/book", first))
        assertFalse(sameBookIdentity(first, "https://a/other"))
    }

    @Test
    fun `download ids key the episode, not the stream url`() {
        val one = videoDownloadId("https://a/book", 1)
        val two = videoDownloadId("https://a/book", 2)
        val otherBook = videoDownloadId("https://b/book", 1)

        assertTrue(one != two)
        assertTrue(one != otherBook)
        // A quality switch or a rotated CDN token must reuse the same row.
        assertEquals(one, videoDownloadId("https://a/book", 1))
    }

    @Test
    fun `a completed episode survives another live download`() {
        val completed = item(7, VideoDownloadState.Completed, 1f)
        val active = item(8, VideoDownloadState.Downloading, 0.3f)

        val merged = mergeDownloadItems(stored = listOf(completed), live = listOf(active))

        assertEquals(2, merged.size)
        assertEquals(VideoDownloadState.Completed, merged.first { it.chapterIndex == 7 }.state)
        assertEquals(VideoDownloadState.Downloading, merged.first { it.chapterIndex == 8 }.state)
    }

    @Test
    fun `live state and progress win over the stored row`() {
        val stored = item(8, VideoDownloadState.Queued, 0f)
        val live = item(8, VideoDownloadState.Downloading, 0.5f)

        val merged = mergeDownloadItems(listOf(stored), listOf(live))

        assertEquals(1, merged.size)
        assertEquals(VideoDownloadState.Downloading, merged.single().state)
        assertEquals(0.5f, merged.single().progress)
    }

    @Test
    fun `a completed row outranks a live duplicate of the same episode`() {
        val completed = item(7, VideoDownloadState.Completed, 1f)
        val duplicate = item(7, VideoDownloadState.Downloading, 0.1f)

        val merged = mergeDownloadItems(listOf(completed), listOf(duplicate))

        assertEquals(1, merged.size)
        assertEquals(VideoDownloadState.Completed, merged.single().state)
    }

    @Test
    fun `a changed stream url keeps the single chapter row`() {
        val requestId = videoDownloadId("https://a/book", 7)
        val rows = listOf(ChapterDownloadRow(requestId, Download.STATE_STOPPED))

        assertFalse(hasConflictingDownload(rows, requestId))
        assertTrue(replaceableChapterDownloadIds(rows, requestId).isEmpty())
    }

    @Test
    fun `a stopped legacy row is replaced instead of duplicated`() {
        val requestId = videoDownloadId("https://a/book", 7)
        val legacyId = "https://a/book\u001Fhttps://cdn/old.m3u8"
        val rows = listOf(ChapterDownloadRow(legacyId, Download.STATE_STOPPED))

        assertFalse(hasConflictingDownload(rows, requestId))
        assertEquals(listOf(legacyId), replaceableChapterDownloadIds(rows, requestId))
    }

    @Test
    fun `active or finished legacy rows are never replaced`() {
        val requestId = videoDownloadId("https://a/book", 7)
        val legacyId = "https://a/book\u001Fhttps://cdn/old.m3u8"
        listOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
            Download.STATE_COMPLETED,
            Download.STATE_REMOVING,
        ).forEach { state ->
            val rows = listOf(ChapterDownloadRow(legacyId, state))

            assertTrue(hasConflictingDownload(rows, requestId))
            assertTrue(replaceableChapterDownloadIds(rows, requestId).isEmpty())
        }
    }

    private fun item(
        chapterIndex: Int,
        state: VideoDownloadState,
        progress: Float,
    ) = VideoDownloadItem(
        bookUrl = "https://a/book",
        chapterIndex = chapterIndex,
        chapterUrl = "https://cdn/$chapterIndex.m3u8",
        state = state,
        progress = progress,
    )
}
