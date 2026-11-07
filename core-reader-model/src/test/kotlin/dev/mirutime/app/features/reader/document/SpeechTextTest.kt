package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    // --- Noise detection -------------------------------------------------

    @Test
    fun `decorative dividers are not spoken`() {
        val dividers = listOf(
            "***",
            "******",
            "===",
            "----",
            "──────",
            "━━━━━━",
            "═════",
            "* * *",
            "• • •",
            "╔══════════╗",
            "💀💀💀",
        )
        dividers.forEach { assertTrue("expected noise: $it", SpeechText.isNoiseBlock(it)) }
    }

    @Test
    fun `litrpg status panels are not spoken`() {
        val panels = listOf(
            "Name: Kaito\nLevel: 12\nHP: 120/150",
            "Имя: Кайто\nУровень: 12\nHP: 120 / 150",
            "生命值: 120\n魔法值: 30",
            "╔════════════╗\n║ HP: 120    ║\n╚════════════╝",
            "HP: 120 / MP: 30\nAttack: 14 / Defense: 9",
        )
        panels.forEach { assertTrue("expected noise: $it", SpeechText.isNoiseBlock(it)) }
    }

    @Test
    fun `chat and log blocks are not spoken`() {
        val logs = listOf(
            "[12:34] Alice: hi\n[12:35] Bob: yo",
            "<Alice> hi there\n<Bob> hello",
            "⟨Alice⟩ hi there\n⟨Bob⟩ hello",
            "**Alice** hi\n**Bob** hello",
        )
        logs.forEach { assertTrue("expected noise: $it", SpeechText.isNoiseBlock(it)) }
    }

    @Test
    fun `single status lines are skipped only when they are stats`() {
        assertEquals(NoiseReason.STATUS_LINE, SpeechText.noiseReason("HP: 120 / MP: 30"))
        assertEquals(NoiseReason.STATUS_LINE, SpeechText.noiseReason("Уровень: 12"))
        assertNull(SpeechText.noiseReason("Том 2: Начало"))
        assertNull(SpeechText.noiseReason("Часть 1: Побег"))
        assertNull(SpeechText.noiseReason("Chapter 5: The Return"))
    }

    @Test
    fun `prose is always spoken`() {
        val prose = listOf(
            "Она остановилась. Ветер стих.",
            "He said: run.",
            "Поле: зелёное, трава высокая.",
            "— Не шути, — сказал он.",
            "Глава 1",
            "Шёл дождь.\nОн шёл всю ночь.",
            "HP was low, but he kept going.",
            "1\n2\n3",
        )
        prose.forEach {
            assertFalse("must stay speech: $it", SpeechText.isNoiseBlock(it))
            assertNull(SpeechText.noiseReason(it))
        }
    }

    @Test
    fun `framed prose is not skipped`() {
        val framed = "╔══════╗\n║ Он вошёл в комнату. ║\n╚══════╝"
        assertNull(SpeechText.noiseReason(framed))
    }

    // --- Masking ---------------------------------------------------------

    @Test
    fun `mask keeps length and character positions`() {
        val text = "*** Hello. ***"
        val masked = SpeechText.mask(text)

        assertEquals(text.length, masked.length)
        assertEquals("    Hello.    ", masked)
        assertEquals(text.indexOf("Hello"), masked.indexOf("Hello"))
    }

    @Test
    fun `mask blanks newlines and edge frames`() {
        assertEquals("a b", SpeechText.mask("a\nb"))
        assertEquals("a  b", SpeechText.mask("a\r\nb"))
        assertEquals("  HP: 120  ", SpeechText.mask("║ HP: 120 ║"))
    }

    @Test
    fun `mask leaves plain prose untouched`() {
        assertEquals("Hello world.", SpeechText.mask("Hello world."))
        assertEquals("— Не шути.", SpeechText.mask("— Не шути."))
    }
}
