package dev.mirutime.app.features.book.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BookPagePresentationTest {

    @Test
    fun `percent keeps honest precision and never fakes progress at zero`() {
        assertEquals("0", formatPercent(0, 1432, Locale.US))
        assertEquals("0.35", formatPercent(5, 1432, Locale.US))
        assertEquals("0.10", formatPercent(1, 1000, Locale.US))
        assertEquals("1.0", formatPercent(1, 100, Locale.US))
        assertEquals("10", formatPercent(1, 10, Locale.US))
        assertEquals("50", formatPercent(1, 2, Locale.US))
    }
}
