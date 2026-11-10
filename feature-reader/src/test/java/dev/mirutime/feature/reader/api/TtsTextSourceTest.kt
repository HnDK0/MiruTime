package dev.mirutime.feature.reader.api

import dev.mirutime.feature.tts.TtsLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextSourceTest {
    private val translated = mapOf(0 to "translated")

    @Test
    fun `normal translation mode speaks translations`() {
        val source = ttsTextSource(
            parallelEnabled = false,
            parallelOrder = "TRANSLATION_FIRST",
            translations = translated,
        )

        assertEquals(TtsLanguage.Translation, source.language)
        assertEquals(translated, source.translations)
    }

    @Test
    fun `parallel original first speaks the original`() {
        val source = ttsTextSource(
            parallelEnabled = true,
            parallelOrder = "ORIGINAL_FIRST",
            translations = translated,
        )

        assertEquals(TtsLanguage.Original, source.language)
        assertEquals(emptyMap<Int, String>(), source.translations)
    }

    @Test
    fun `parallel translation first speaks the translation`() {
        val source = ttsTextSource(
            parallelEnabled = true,
            parallelOrder = "TRANSLATION_FIRST",
            translations = translated,
        )

        assertEquals(TtsLanguage.Translation, source.language)
        assertEquals(translated, source.translations)
    }

    @Test
    fun `parallel translation first with nothing translated speaks the original`() {
        val source = ttsTextSource(
            parallelEnabled = true,
            parallelOrder = "TRANSLATION_FIRST",
            translations = null,
        )

        // Translation off, no pair, or this chapter not translated: there is no
        // translated text, so the voice follows the original text.
        assertEquals(TtsLanguage.Original, source.language)
        assertEquals(emptyMap<Int, String>(), source.translations)
    }

    @Test
    fun `parallel translation first speaks the translation only with a translated chapter`() {
        val emptyChapter = ttsTextSource(
            parallelEnabled = true,
            parallelOrder = "TRANSLATION_FIRST",
            translations = emptyMap(),
        )
        val translatedChapter = ttsTextSource(
            parallelEnabled = true,
            parallelOrder = "TRANSLATION_FIRST",
            translations = mapOf(0 to "translated"),
        )

        assertEquals(TtsLanguage.Original, emptyChapter.language)
        assertEquals(TtsLanguage.Translation, translatedChapter.language)
    }

    @Test
    fun `missing translations speak the original`() {
        val source = ttsTextSource(false, "ORIGINAL_FIRST", null)

        assertEquals(TtsLanguage.Original, source.language)
        assertEquals(emptyMap<Int, String>(), source.translations)
    }
}
