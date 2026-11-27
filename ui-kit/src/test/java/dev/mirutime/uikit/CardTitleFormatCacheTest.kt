package dev.mirutime.uikit

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTitleFormatCacheTest {

    private val style = TitleStyleKey.of(TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))

    @Test
    fun `repeat format with the same key performs no measurement`() {
        val title = "Герой, жаждущий мести, уничтожит врагов силой тьмы"
        val measured = mutableListOf<String>()
        val measure: (String) -> Int = { text ->
            measured += text
            text.length
        }

        val first = CardTitleFormatCache.format(title, 2, 15, style, measure)
        val callsAfterFirst = measured.size
        val second = CardTitleFormatCache.format(title, 2, 15, style, measure)

        assertEquals(first, second)
        assertTrue(callsAfterFirst > 0)
        assertEquals(callsAfterFirst, measured.size)
    }

    @Test
    fun `every distinct probe is measured exactly once per format`() {
        val measured = mutableListOf<String>()
        val measure: (String) -> Int = { text ->
            measured += text
            text.length
        }

        CardTitleFormatCache.format(
            title = "Герой, жаждущий мести, уничтожит врагов силой тьмы",
            maxLines = 2,
            widthPx = 17,
            style = style,
            measure = measure,
        )

        val repeated = measured.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("repeated probes: $repeated", repeated.isEmpty())
        // Whole title + space + one probe per distinct token/broken head.
        assertTrue("probes=${measured.size}", measured.size <= 12)
    }

    @Test
    fun `different width and style are cached separately`() {
        val title = "Blade of the Discarded"
        var calls = 0
        val measure: (String) -> Int = { text ->
            calls++
            text.length
        }

        val narrow = CardTitleFormatCache.format(title, 2, 18, style, measure)
        val wide = CardTitleFormatCache.format(title, 2, 40, style, measure)
        val otherStyle = CardTitleFormatCache.format(
            title = title,
            maxLines = 2,
            widthPx = 18,
            style = TitleStyleKey.of(TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)),
            measure = measure,
        )

        assertEquals("Blade of the\nDiscarded", narrow)
        assertEquals(title, wide)
        assertEquals(narrow, otherStyle)
        assertTrue(calls > 0)
    }
}
