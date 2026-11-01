package dev.mirutime.app.features.video

import dev.mirutime.app.features.video.domain.videoResumePositionMs
import dev.mirutime.app.features.video.domain.videoShouldMarkRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProgressTest {
    @Test
    fun `95 percent marks the episode read`() {
        assertTrue(videoShouldMarkRead(95_000, 100_000))
        assertFalse(videoShouldMarkRead(94_999, 100_000))
        assertFalse(videoShouldMarkRead(1_000, 0))
    }

    @Test
    fun `unfinished position resumes`() {
        assertEquals(30_000L, videoResumePositionMs(30_000, 100_000))
    }

    @Test
    fun `watched episode starts over`() {
        assertEquals(0L, videoResumePositionMs(96_000, 100_000))
        assertEquals(0L, videoResumePositionMs(-1, 100_000))
    }
}
