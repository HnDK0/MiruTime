package dev.mirutime.app.features.library.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStylePickerRailTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `picker is a horizontal 120dp rail, not a 3-column grid`() {
        val text = source("src/main/java/dev/mirutime/app/design/components/CardStylePicker.kt")

        assertTrue(text.contains("LazyRow"))
        assertTrue(text.contains("120.dp"))
        assertFalse(text.contains("chunked(3)"))
    }
}
