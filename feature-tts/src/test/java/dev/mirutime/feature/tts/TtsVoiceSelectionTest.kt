package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Test

// Independent original/translated voice slots: a session speaks one language,
// so it picks the matching slot; an unset translated slot falls back to the
// original voice (FR-040..042).
class TtsVoiceSelectionTest {

    @Test
    fun `translated session uses the translated slot when set`() {
        val settings = TtsSettings(
            voiceId = "orig",
            engineId = "eng-orig",
            translatedVoiceId = "trans",
            translatedEngineId = "eng-trans",
        )

        assertEquals(
            TtsVoiceSelection("trans", "eng-trans"),
            settings.voiceForSession(translated = true),
        )
        assertEquals(
            TtsVoiceSelection("orig", "eng-orig"),
            settings.voiceForSession(translated = false),
        )
    }

    @Test
    fun `an unset original slot stays on the engine default`() {
        assertEquals(
            TtsVoiceSelection(null, null),
            TtsSettings().voiceForSession(translated = false),
        )
    }

    @Test
    fun `an unset translated slot falls back to the original voice`() {
        val settings = TtsSettings(voiceId = "orig", engineId = "eng-orig")

        assertEquals(
            TtsVoiceSelection("orig", "eng-orig"),
            settings.voiceForSession(translated = true),
        )
    }

    @Test
    fun `changing one slot does not touch the other`() {
        val settings = TtsSettings(voiceId = "orig", translatedVoiceId = "trans")
        val changed = settings.copy(translatedVoiceId = "trans2")

        assertEquals("orig", changed.voiceId)
        assertEquals("trans2", changed.translatedVoiceId)
    }
}
