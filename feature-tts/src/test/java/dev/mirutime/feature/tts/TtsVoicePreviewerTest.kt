package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoicePreviewerTest {

    @Test
    fun `system default resolves to the engine default`() {
        assertEquals(
            "default",
            resolvePreviewVoiceId(
                voiceId = null,
                defaultVoiceId = "default",
                voiceIds = listOf("named", "default"),
            ),
        )
    }

    @Test
    fun `system default falls back to the first available voice`() {
        assertEquals(
            "named",
            resolvePreviewVoiceId(
                voiceId = null,
                defaultVoiceId = null,
                voiceIds = listOf("named"),
            ),
        )
    }

    @Test
    fun `stale default id falls back to the first available voice`() {
        assertEquals(
            "named",
            resolvePreviewVoiceId(
                voiceId = null,
                defaultVoiceId = "gone",
                voiceIds = listOf("named"),
            ),
        )
    }

    @Test
    fun `named voice wins when present`() {
        assertEquals(
            "named",
            resolvePreviewVoiceId(
                voiceId = "named",
                defaultVoiceId = "default",
                voiceIds = listOf("default", "named"),
            ),
        )
    }

    @Test
    fun `missing named voice does not fall back to a different voice`() {
        assertNull(
            resolvePreviewVoiceId(
                voiceId = "gone",
                defaultVoiceId = "default",
                voiceIds = listOf("default", "named"),
            ),
        )
    }
}
