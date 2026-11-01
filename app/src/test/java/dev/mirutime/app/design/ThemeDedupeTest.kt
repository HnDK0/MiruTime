package dev.mirutime.app.design

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeDedupeTest {

    @Test
    fun `presets dedupe by url keeping the first`() {
        val presets = listOf(
            PresetBackground("1_Backgrounds.jpeg", "https://cdn/1.jpeg"),
            PresetBackground("copy.jpeg", "https://cdn/1.jpeg"),
            PresetBackground("2_Backgrounds.jpeg", "https://cdn/2.jpeg"),
        )

        assertEquals(
            listOf("https://cdn/1.jpeg", "https://cdn/2.jpeg"),
            dedupePresets(presets).map { it.url },
        )
    }

    @Test
    fun `saved backgrounds dedupe by path`() {
        assertEquals(
            listOf("/files/a.jpg", "https://cdn/b.jpg"),
            dedupeBackgroundPaths(
                listOf("/files/a.jpg", "/files/a.jpg", "https://cdn/b.jpg"),
            ),
        )
    }
}
