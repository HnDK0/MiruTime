package dev.mirutime.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleTest {
    @Test
    fun `defaults match the approved reader tokens`() {
        val s = ReaderStyle.Default
        assertEquals("serif", s.fontFamily)
        assertEquals(17, s.fontSizeSp)
        assertEquals(1.55f, s.lineHeight, 0.001f)
        assertEquals(16, s.paragraphSpacingDp)
        assertEquals(20, s.marginStartDp)
        assertEquals(20, s.marginEndDp)
        assertEquals(0, s.marginTopDp)
        assertEquals(0, s.marginBottomDp)
        assertEquals(0f, s.letterSpacingEm, 0.001f)
        assertFalse(s.justify)
        assertFalse(s.splitSentences)
        assertEquals(40, s.autoScrollSpeed)
        assertTrue(s.fullscreen)
        assertTrue(s.selectableText)
    }

    @Test
    fun `sanitize clamps every numeric range`() {
        val s = ReaderStyle(
            fontSizeSp = 99,
            lineHeight = 9f,
            paragraphSpacingDp = -5,
            marginStartDp = 500,
            letterSpacingEm = 2f,
            autoScrollSpeed = 7,
        ).sanitized()
        assertEquals(32, s.fontSizeSp)
        assertEquals(2.5f, s.lineHeight, 0.001f)
        assertEquals(0, s.paragraphSpacingDp)
        assertEquals(100, s.marginStartDp)
        assertEquals(0.3f, s.letterSpacingEm, 0.001f)
        assertEquals(10, s.autoScrollSpeed)
    }

    @Test
    fun `auto text color follows the background`() {
        val dark = ReaderStyle(backgroundId = "night", textColor = null)
        assertEquals(ReaderPalette.background("night").textArgb, dark.effectiveTextColor())
        val custom = dark.copy(textColor = 0xFF00FF00)
        assertEquals(0xFF00FF00, custom.effectiveTextColor())
    }

    @Test
    fun `letter spacing converts em to sp`() {
        assertEquals(4.2f, ReaderStyle(fontSizeSp = 14, letterSpacingEm = 0.3f).letterSpacingSp(), 0.01f)
    }

    @Test
    fun `palette has unique ids and 14 backgrounds`() {
        assertEquals(14, ReaderPalette.backgrounds.size)
        assertEquals(ReaderPalette.backgrounds.size, ReaderPalette.backgrounds.map { it.id }.distinct().size)
        assertEquals(12, ReaderPalette.textColors.size)
        assertEquals(12, ReaderPalette.highlightColors.size)
    }

    @Test
    fun `unknown background falls back to auto`() {
        assertEquals(ReaderPalette.background("auto"), ReaderPalette.background("nope"))
    }
}
