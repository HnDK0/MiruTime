package dev.mirutime.uikit

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the photo plate alpha: a narrower plate lets the picture through and
// must not drop the light reading ink below the WCAG body floor (4.5:1) in the
// worst case (the plate composited over a white photo).
class PlateContrastTest {

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `plate alpha keeps light ink readable over the worst case`() {
        val surfaces = listOf(
            Color(0xFF1A2B28),
            Color(0xFF223A35),
            Color(0xFF2C4A44),
        )
        val backdrops = plateBackdrops(surfaces, PlateAlpha)
        // Each plate is checked over black and over white.
        assertEquals(surfaces.size * 2, backdrops.size)
        val ink = Color(0xFFF0F5F4)
        backdrops.forEach { backdrop ->
            val contrast = ratio(ink, backdrop)
            assertTrue(
                "light ink on $backdrop is $contrast, expected >= 4.5",
                contrast >= 4.5,
            )
        }
    }
}
