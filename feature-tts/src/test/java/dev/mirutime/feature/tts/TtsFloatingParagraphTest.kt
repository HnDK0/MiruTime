package dev.mirutime.feature.tts

import dev.mirutime.feature.tts.ui.floatingParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsFloatingParagraphTest {
    private val original = "Original block."
    private val translation = "Translated block."

    @Test
    fun `spoken mode shows the spoken sentence with its own range`() {
        listOf(true, false).forEach { spokenIsTranslation ->
            val paragraph = floatingParagraph(
                mode = "tts",
                spokenText = "Spoken sentence.",
                paragraphText = translation,
                counterpartText = original,
                spokenIsTranslation = spokenIsTranslation,
            )

            assertEquals("Spoken sentence.", paragraph.primary)
            assertNull(paragraph.secondary)
            assertTrue(paragraph.sentenceRange)
            assertFalse(paragraph.blockRange)
        }
    }

    @Test
    fun `both mode keeps spoken first and counterpart second`() {
        val translatedSpoken = floatingParagraph(
            mode = "both",
            spokenText = "Spoken sentence.",
            paragraphText = translation,
            counterpartText = original,
            spokenIsTranslation = true,
        )
        assertEquals(translation, translatedSpoken.primary)
        assertEquals(original, translatedSpoken.secondary)
        assertTrue(translatedSpoken.blockRange)
        assertFalse(translatedSpoken.sentenceRange)

        val originalSpoken = floatingParagraph(
            mode = "both",
            spokenText = "Spoken sentence.",
            paragraphText = original,
            counterpartText = translation,
            spokenIsTranslation = false,
        )
        assertEquals(original, originalSpoken.primary)
        assertEquals(translation, originalSpoken.secondary)
        assertTrue(originalSpoken.blockRange)
        assertFalse(originalSpoken.sentenceRange)
    }

    @Test
    fun `inverse mode shows the translation when TTS speaks the translation`() {
        val paragraph = floatingParagraph(
            mode = "inverse",
            spokenText = "Spoken sentence.",
            paragraphText = translation,
            counterpartText = original,
            spokenIsTranslation = true,
        )

        assertEquals(translation, paragraph.primary)
        assertNull(paragraph.secondary)
        assertTrue(paragraph.blockRange)
        assertFalse(paragraph.sentenceRange)
    }

    @Test
    fun `inverse mode shows the counterpart translation when TTS speaks the original`() {
        val paragraph = floatingParagraph(
            mode = "inverse",
            spokenText = "Spoken sentence.",
            paragraphText = original,
            counterpartText = translation,
            spokenIsTranslation = false,
        )

        assertEquals(translation, paragraph.primary)
        assertNull(paragraph.secondary)
        assertFalse(paragraph.blockRange)
        assertFalse(paragraph.sentenceRange)
    }

    @Test
    fun `inverse mode falls back to the spoken sentence without a block range`() {
        val paragraph = floatingParagraph(
            mode = "inverse",
            spokenText = "Spoken sentence.",
            paragraphText = null,
            counterpartText = original,
            spokenIsTranslation = true,
        )

        assertEquals("Spoken sentence.", paragraph.primary)
        assertFalse(paragraph.blockRange)
        assertFalse(paragraph.sentenceRange)
    }

    @Test
    fun `inverse mode keeps text when the translation disappears`() {
        val paragraph = floatingParagraph(
            mode = "inverse",
            spokenText = "Spoken sentence.",
            paragraphText = original,
            counterpartText = null,
            spokenIsTranslation = false,
        )

        assertEquals(original, paragraph.primary)
        assertFalse(paragraph.blockRange)
        assertFalse(paragraph.sentenceRange)
    }
}
