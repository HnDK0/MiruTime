package dev.mirutime.feature.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineErrorTest {
    @Test
    fun `preview errors do not fail the active session`() {
        assertFalse(shouldReportPlaybackError("tts-preview"))
        assertFalse(shouldReportPlaybackError(null))
        assertFalse(shouldReportPlaybackError("foreign"))
    }

    @Test
    fun `numeric playback errors still fail the session`() {
        assertTrue(shouldReportPlaybackError("42"))
    }
}
