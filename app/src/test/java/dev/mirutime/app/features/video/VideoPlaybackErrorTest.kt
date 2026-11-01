package dev.mirutime.app.features.video

import androidx.media3.common.Format
import dev.mirutime.app.features.video.domain.VideoPlaybackErrorKind
import dev.mirutime.app.features.video.domain.VideoVariantStatus
import dev.mirutime.app.features.video.domain.isDecoderFallbackError
import dev.mirutime.app.features.video.domain.pendingVariantProbes
import dev.mirutime.app.features.video.domain.videoExternalMimeType
import dev.mirutime.app.features.video.domain.videoPlaybackErrorKind
import dev.mirutime.app.features.video.domain.videoRestartPositionMs
import dev.mirutime.app.features.video.domain.videoVariantChoices
import dev.mirutime.app.features.video.ui.videoTrackLabel
import dev.mirutime.core.api.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackErrorTest {

    private fun stream(url: String, quality: String? = null, mime: String? = null) =
        VideoStream(url = url, quality = quality, mime = mime)

    @Test
    fun `error code ranges separate source from codec`() {
        assertEquals(VideoPlaybackErrorKind.Source, videoPlaybackErrorKind(2001))
        assertEquals(VideoPlaybackErrorKind.Source, videoPlaybackErrorKind(3001))
        assertEquals(VideoPlaybackErrorKind.Codec, videoPlaybackErrorKind(4001))
        assertEquals(VideoPlaybackErrorKind.Codec, videoPlaybackErrorKind(4003))
        assertEquals(VideoPlaybackErrorKind.Other, videoPlaybackErrorKind(1000))
    }

    @Test
    fun `only decoder init and decoding failures trigger software fallback`() {
        assertTrue(isDecoderFallbackError(4001))
        assertTrue(isDecoderFallbackError(4003))
        assertFalse(isDecoderFallbackError(4004))
        assertFalse(isDecoderFallbackError(2001))
    }

    @Test
    fun `retry keeps the current position and falls back when unknown`() {
        assertEquals(30_000L, videoRestartPositionMs(30_000, 5_000))
        assertEquals(5_000L, videoRestartPositionMs(0, 5_000))
        assertEquals(0L, videoRestartPositionMs(-1, -1))
    }

    @Test
    fun `external MIME is HLS for m3u8 and video otherwise`() {
        assertEquals("application/x-mpegURL", videoExternalMimeType(stream("https://cdn/a.m3u8")))
        assertEquals(
            "application/x-mpegURL",
            videoExternalMimeType(stream("https://cdn/a", mime = "application/x-mpegURL")),
        )
        assertEquals("video/*", videoExternalMimeType(stream("https://cdn/a.mp4")))
        assertEquals("video/*", videoExternalMimeType(stream("https://cdn/a.mkv")))
    }

    @Test
    fun `variant grouping marks dead rows and falls back to source label`() {
        val first = stream("https://cdn/720.m3u8", "720p")
        val second = stream("https://cdn/1080.m3u8", "1080p")
        val third = stream("https://cdn/live.m3u8")

        val choices = videoVariantChoices(
            streams = listOf(first, second, third),
            statuses = mapOf(
                first.url to VideoVariantStatus.DEAD,
                second.url to VideoVariantStatus.OK,
                third.url to VideoVariantStatus.UNKNOWN,
            ),
        )

        assertEquals(listOf(0, 1, 2), choices.map { it.index })
        assertEquals(listOf("720p", "1080p", ""), choices.map { it.label })
        assertEquals(listOf(true, false, false), choices.map { it.dead })
    }

    @Test
    fun `probe cache skips OK and UNKNOWN and rechecks DEAD only on demand`() {
        val dead = stream("https://cdn/dead.m3u8")
        val ok = stream("https://cdn/ok.m3u8")
        val unknown = stream("https://cdn/unknown.m3u8")
        val fresh = stream("https://cdn/fresh.m3u8")
        val streams = listOf(dead, ok, unknown, fresh)
        val statuses = mapOf(
            dead.url to VideoVariantStatus.DEAD,
            ok.url to VideoVariantStatus.OK,
            unknown.url to VideoVariantStatus.UNKNOWN,
        )

        assertEquals(
            listOf(fresh.url),
            pendingVariantProbes(streams, statuses, includeDead = false).map { it.url },
        )
        assertEquals(
            listOf(dead.url, fresh.url),
            pendingVariantProbes(streams, statuses, includeDead = true).map { it.url },
        )
    }

    @Test
    fun `video format label uses height`() {
        assertEquals("720p", videoTrackLabel(Format.Builder().setHeight(720).build()))
    }
}
