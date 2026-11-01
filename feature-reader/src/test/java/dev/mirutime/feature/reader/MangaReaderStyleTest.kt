package dev.mirutime.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderStyleTest {
    @Test
    fun `defaults match the approved manga tokens`() {
        val s = MangaReaderStyle.Default
        assertEquals(MangaReadingMode.Webtoon, s.mode)
        assertEquals(MangaReadingDirection.Ltr, s.direction)
        assertFalse(s.tapZoneInvert)
        assertTrue(s.showPageCounter)
        assertEquals(MangaWebtoonPadding.Medium, s.webtoonPadding)
        assertTrue(s.doubleTapZoom)
        assertEquals(1f, s.brightness, 0.001f)
        assertEquals(MangaColorFilter.None, s.colorFilter)
    }

    @Test
    fun `sanitize clamps brightness`() {
        assertEquals(0.2f, MangaReaderStyle(brightness = -2f).sanitized().brightness, 0.001f)
        assertEquals(1f, MangaReaderStyle(brightness = 9f).sanitized().brightness, 0.001f)
        assertEquals(0.55f, MangaReaderStyle(brightness = 0.55f).sanitized().brightness, 0.001f)
    }

    @Test
    fun `unknown persisted names fall back to defaults`() {
        assertEquals(MangaReadingMode.Webtoon, parseMangaReadingMode(null))
        assertEquals(MangaReadingMode.Webtoon, parseMangaReadingMode("spread"))
        assertEquals(MangaReadingMode.Webtoon, parseMangaReadingMode("webtoon"))
        assertEquals(MangaReadingMode.Paged, parseMangaReadingMode("Paged"))
        assertEquals(MangaReadingDirection.Ltr, parseMangaReadingDirection(""))
        assertEquals(MangaReadingDirection.Rtl, parseMangaReadingDirection("Rtl"))
        assertEquals(MangaColorFilter.None, parseMangaColorFilter("noir"))
        assertEquals(MangaColorFilter.Sepia, parseMangaColorFilter("sepia"))
        assertEquals(MangaWebtoonPadding.Medium, parseMangaWebtoonPadding(null))
        assertEquals(MangaWebtoonPadding.Large, parseMangaWebtoonPadding("large"))
    }

    @Test
    fun `tap side splits the width into quarters`() {
        assertEquals(MangaTapSide.Left, mangaTapSide(10f, 400f))
        assertEquals(MangaTapSide.Center, mangaTapSide(200f, 400f))
        assertEquals(MangaTapSide.Right, mangaTapSide(390f, 400f))
        assertEquals(MangaTapSide.Center, mangaTapSide(10f, 0f))
    }

    @Test
    fun `ltr taps advance on the right`() {
        val style = MangaReaderStyle(direction = MangaReadingDirection.Ltr)
        assertFalse(style.leftTapAdvances())
        assertEquals(MangaPageTurn.Previous, style.tapTurn(MangaTapSide.Left))
        assertEquals(MangaPageTurn.Next, style.tapTurn(MangaTapSide.Right))
        assertNull(style.tapTurn(MangaTapSide.Center))
    }

    @Test
    fun `rtl taps advance on the left`() {
        val style = MangaReaderStyle(direction = MangaReadingDirection.Rtl)
        assertTrue(style.leftTapAdvances())
        assertEquals(MangaPageTurn.Next, style.tapTurn(MangaTapSide.Left))
        assertEquals(MangaPageTurn.Previous, style.tapTurn(MangaTapSide.Right))
    }

    @Test
    fun `inverted taps flip both directions`() {
        val ltr = MangaReaderStyle(
            direction = MangaReadingDirection.Ltr,
            tapZoneInvert = true,
        )
        assertTrue(ltr.leftTapAdvances())
        assertEquals(MangaPageTurn.Next, ltr.tapTurn(MangaTapSide.Left))
        assertEquals(MangaPageTurn.Previous, ltr.tapTurn(MangaTapSide.Right))

        val rtl = MangaReaderStyle(
            direction = MangaReadingDirection.Rtl,
            tapZoneInvert = true,
        )
        assertFalse(rtl.leftTapAdvances())
        assertEquals(MangaPageTurn.Previous, rtl.tapTurn(MangaTapSide.Left))
        assertEquals(MangaPageTurn.Next, rtl.tapTurn(MangaTapSide.Right))
    }

    @Test
    fun `chapter end advances or ends instead of looping`() {
        assertEquals(MangaEndAction.Continue, mangaEndAction(0, 4, canNextChapter = true))
        assertEquals(MangaEndAction.NextChapter, mangaEndAction(4, 4, canNextChapter = true))
        assertEquals(MangaEndAction.EndOfBook, mangaEndAction(4, 4, canNextChapter = false))
    }

    @Test
    fun `webtoon anchor clamps to the page range`() {
        assertEquals(0, webtoonAnchorIndex(-3, 10))
        assertEquals(4, webtoonAnchorIndex(4, 10))
        assertEquals(9, webtoonAnchorIndex(42, 10))
        assertEquals(0, webtoonAnchorIndex(2, 0))
    }
}
