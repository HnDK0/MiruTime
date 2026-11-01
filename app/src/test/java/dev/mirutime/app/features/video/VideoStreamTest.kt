package dev.mirutime.app.features.video

import androidx.media3.common.MimeTypes
import dev.mirutime.app.features.video.domain.selectVideoStream
import dev.mirutime.app.features.video.domain.subtitleMimeType
import dev.mirutime.app.features.video.domain.videoMimeType
import dev.mirutime.app.features.video.domain.videoRequestHeaders
import dev.mirutime.app.features.video.domain.videoStreamLabel
import dev.mirutime.core.api.SubtitleTrack
import dev.mirutime.core.api.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VideoStreamTest {
    private fun stream(url: String, quality: String? = null, mime: String? = null) =
        VideoStream(url = url, quality = quality, mime = mime)

    @Test
    fun `quality match wins over order`() {
        val first = stream("https://a/360.mp4", "360p")
        val target = stream("https://b/1080.m3u8", "1080p")
        assertSame(target, selectVideoStream(listOf(first, target), "1080p"))
    }

    @Test
    fun `missing quality falls back to first source`() {
        val first = stream("https://a/720.m3u8", "720p")
        assertSame(first, selectVideoStream(listOf(first, stream("https://b/480.mp4", "480p")), "1080p"))
    }

    @Test
    fun `mime hints map to media3 types`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeType(stream("https://h/play", mime = "hls")))
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeType(stream("https://h/play.m3u8?t=1")))
        assertEquals(MimeTypes.APPLICATION_MPD, videoMimeType(stream("https://h/play", mime = "dash")))
        assertEquals(MimeTypes.VIDEO_MP4, videoMimeType(stream("https://h/play.mp4")))
        assertNull(videoMimeType(stream("https://h/play", mime = "unknown")))
    }

    @Test
    fun `subtitle mime follows the extension`() {
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType(SubtitleTrack("https://s/ru.vtt")))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType(SubtitleTrack("https://s/ru.srt")))
    }

    @Test
    fun `stream headers win over plugin defaults`() {
        val merged = videoRequestHeaders(
            mapOf("Referer" to "https://site/ep", "Cookie" to "cf_clearance=1", "User-Agent" to "app"),
            mapOf("User-Agent" to "plugin"),
        )
        assertEquals("https://site/ep", merged["Referer"])
        assertEquals("cf_clearance=1", merged["Cookie"])
        assertEquals("plugin", merged["User-Agent"])
    }

    @Test
    fun `label uses quality then mime then position`() {
        assertEquals("1080p", videoStreamLabel(stream("u", "1080p")))
        assertEquals("hls", videoStreamLabel(stream("u", mime = "hls")))
        assertEquals("", videoStreamLabel(stream("u")))
    }
}
