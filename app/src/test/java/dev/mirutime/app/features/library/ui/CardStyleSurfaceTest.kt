package dev.mirutime.app.features.library.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStyleSurfaceTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `library filter embeds the one picker, not A B D chips`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")

        assertFalse(text.contains("CardStyleOptions"))
        assertFalse(text.contains("lib_card_a"))
        assertTrue(text.contains("CardStylePicker"))
    }

    @Test
    fun `settings opens the same picker, not the old strip sheet`() {
        val text = source("src/main/java/dev/mirutime/app/features/settings/ui/LibraryUpdatesSettingsScreens.kt")

        assertFalse(text.contains("st_library_strip"))
        assertFalse(text.contains("stripSheet"))
        assertTrue(text.contains("CardStylePicker"))
    }

    @Test
    fun `picker does not offer the duplicate Share preset`() {
        val text = source("src/main/java/dev/mirutime/app/design/components/CardStylePresets.kt")
        val list = text.substringAfter("GridStyles = listOf(").substringBefore(")")
        assertFalse(list.contains("CardStyle.Share"))
    }
}
