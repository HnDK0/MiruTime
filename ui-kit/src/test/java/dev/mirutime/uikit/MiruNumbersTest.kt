package dev.mirutime.uikit

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiruNumbersTest {

    private val ar = Locale.forLanguageTag("ar")

    /**
     * The digits and separators follow the interface language for German and
     * Russian; Arabic is checked against [String.format] itself, because the
     * digit glyphs depend on the platform's ICU data (Latin on the host JVM,
     * Arabic-Indic on Android) while the invariant that matters is that a
     * counter here and a `%d`/`%.1f` from `strings.xml` always agree.
     */
    @Test
    fun `digits and separators follow the locale`() {
        assertEquals("12.5", miruDecimal(12.5, 1, Locale.US))
        assertEquals("12,5", miruDecimal(12.5, 1, Locale.GERMAN))
        assertEquals("12,5", miruDecimal(12.5, 1, Locale.forLanguageTag("ru")))
        assertEquals(String.format(ar, "%.1f", 12.5), miruDecimal(12.5, 1, ar))
        assertEquals(String.format(ar, "%.0f", 12.0), miruDecimal(12, 0, ar))
    }

    /** Ratings and counters drop trailing zeros instead of padding them. */
    @Test
    fun `trailing zeros are trimmed but significant digits stay`() {
        assertEquals("5", miruDecimal(5f, 1, Locale.US))
        assertEquals("4.5", miruDecimal(4.5f, 1, Locale.US))
        assertEquals("0.35", miruDecimal(0.35, 2, Locale.US))
        assertEquals("1.5", miruDecimal(1.5, 2, Locale.US))
        // Trimming must remove the separator too, whatever glyph it is.
        assertEquals(String.format(ar, "%.0f", 12.0), miruDecimal(12.0, 2, ar))
    }

    /** No thousands separators: these strings sit in compact chips and rows. */
    @Test
    fun `grouping stays off`() {
        assertEquals("1432", miruDecimal(1432, 0, Locale.US))
        // No separators at all: the formatted value is exactly the plain integer.
        assertEquals(String.format(ar, "%.0f", 1432.0), miruDecimal(1432, 0, ar))
        assertTrue(miruDecimal(1432, 0, ar).none { it == ',' || it == '٬' })
    }
}
