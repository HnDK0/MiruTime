package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

class MiruSliderRtlTest {

    private val width = 200f
    private val radius = 10f

    @Test
    fun `ltr grows from the start edge`() {
        assertEquals(0f, sliderFractionAt(radius, width, radius, rtl = false), 0.001f)
        assertEquals(1f, sliderFractionAt(width - radius, width, radius, rtl = false), 0.001f)
        assertEquals(0.5f, sliderFractionAt(width / 2f, width, radius, rtl = false), 0.001f)
    }

    @Test
    fun `rtl grows from the opposite edge`() {
        // Maximum sits at the start edge, which is the right one in RTL.
        assertEquals(0f, sliderFractionAt(width - radius, width, radius, rtl = true), 0.001f)
        assertEquals(1f, sliderFractionAt(radius, width, radius, rtl = true), 0.001f)
        assertEquals(0.5f, sliderFractionAt(width / 2f, width, radius, rtl = true), 0.001f)
    }

    @Test
    fun `the same touch maps to mirrored fractions`() {
        listOf(0f, 37f, 100f, 151f, 190f, 200f).forEach { x ->
            val ltr = sliderFractionAt(x, width, radius, rtl = false)
            val rtl = sliderFractionAt(x, width, radius, rtl = true)
            assertEquals("x=$x", 1f, ltr + rtl, 0.001f)
        }
    }

    @Test
    fun `a degenerate width never divides by zero`() {
        val tiny = radius * 1.5f
        assertEquals(0f, sliderFractionAt(0f, tiny, radius, rtl = false), 0.001f)
        assertEquals(0f, sliderFractionAt(tiny, tiny, radius, rtl = true), 0.001f)
    }
}
