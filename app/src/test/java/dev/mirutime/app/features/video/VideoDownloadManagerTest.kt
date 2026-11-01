package dev.mirutime.app.features.video

import dev.mirutime.app.features.video.data.VideoDownloadAction
import dev.mirutime.app.features.video.data.VideoDownloadData
import dev.mirutime.app.features.video.data.VideoDownloadMeta
import dev.mirutime.app.features.video.data.VideoDownloadState
import dev.mirutime.app.features.video.data.VideoDownloaderKind
import dev.mirutime.app.features.video.data.isCancellableVideoState
import dev.mirutime.app.features.video.data.isPausableVideoState
import dev.mirutime.app.features.video.data.videoDownloadActions
import dev.mirutime.app.features.video.data.videoDownloadId
import dev.mirutime.app.features.video.data.videoDownloadState
import dev.mirutime.app.features.video.data.videoDownloaderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `download ids keep book and episode distinct`() {
        val one = videoDownloadId("https://a/book", "https://a/1.m3u8")
        val two = videoDownloadId("https://a/book", "https://a/2.m3u8")
        val otherBook = videoDownloadId("https://b/book", "https://a/1.m3u8")

        assertTrue(one != two)
        assertTrue(one != otherBook)
    }
}
