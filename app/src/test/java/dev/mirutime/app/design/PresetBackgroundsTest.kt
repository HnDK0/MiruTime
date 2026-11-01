package dev.mirutime.app.design

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetBackgroundsTest {

    private val cached = listOf(
        PresetBackground("1_Backgrounds.jpeg", "https://cdn/1.jpeg"),
        PresetBackground("2_Backgrounds.jpeg", "https://cdn/2.jpeg"),
    )

    @Test
    fun `open path reads the cache without a listing fetch or a download`() = runTest {
        var fetches = 0
        var downloads = 0

        val result = loadPresetListing(
            cached = cached,
            refresh = false,
            fetch = { fetches++; null },
            save = { error("open path must not write the listing") },
            download = { downloads++; true },
        )

        assertEquals(cached, result.presets)
        assertFalse(result.listingFetched)
        assertEquals(0, result.downloaded)
        assertEquals(0, fetches)
        assertEquals(0, downloads)
    }

    @Test
    fun `refresh downloads every listed picture`() = runTest {
        val fresh = listOf(
            PresetBackground("3_Backgrounds.jpeg", "https://cdn/3.jpeg"),
            PresetBackground("4_Backgrounds.jpeg", "https://cdn/4.jpeg"),
            PresetBackground("5_Backgrounds.jpeg", "https://cdn/5.jpeg"),
        )
        var fetches = 0
        var saved: List<PresetBackground>? = null
        val downloaded = mutableListOf<String>()

        val result = loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = { fetches++; fresh },
            save = { saved = it },
            download = { preset -> downloaded += preset.url; true },
        )

        assertEquals(fresh, result.presets)
        assertEquals(fresh, saved)
        assertTrue(result.listingFetched)
        assertEquals(3, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, fetches)
        assertEquals(fresh.map { it.url }, downloaded)
    }

    @Test
    fun `a failed picture keeps the rest and the listing cache`() = runTest {
        val fresh = listOf(
            PresetBackground("3_Backgrounds.jpeg", "https://cdn/3.jpeg"),
            PresetBackground("4_Backgrounds.jpeg", "https://cdn/4.jpeg"),
            PresetBackground("5_Backgrounds.jpeg", "https://cdn/5.jpeg"),
        )
        var saved: List<PresetBackground>? = null

        val result = loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = { fresh },
            save = { saved = it },
            download = { preset -> preset.url != "https://cdn/4.jpeg" },
        )

        assertEquals(fresh, result.presets)
        assertEquals(fresh, saved)
        assertEquals(2, result.downloaded)
        assertEquals(1, result.failed)
    }

    @Test
    fun `tap uses the cached file and downloads only a missing one`() = runTest {
        val cachedFile = File("cached.jpg")
        var downloads = 0

        assertEquals(cachedFile, presetFileForUse(cachedFile) { downloads++; null })
        assertEquals(0, downloads)

        val fetched = File("fetched.jpg")
        assertEquals(fetched, presetFileForUse(null) { downloads++; fetched })
        assertEquals(1, downloads)
    }

    @Test
    fun `dedupe removes a preset copy and keeps every other entry`() {
        val presetCopy = "/files/backgrounds/361d79d68fb9533a.jpg"
        val userBackground = "/files/backgrounds/0e2c4aba2b8667f6.jpg"
        val remote = "https://cdn/cover.jpg"
        val removed = mutableListOf<String>()

        val kept = dedupePresetCopies(
            library = listOf(presetCopy, userBackground, remote),
            presetSavedPaths = setOf(presetCopy),
            onDuplicate = { removed += it },
        )

        assertEquals(listOf(userBackground, remote), kept)
        assertEquals(listOf(presetCopy), removed)
    }

    @Test
    fun `applying a preset uses the in-place file and skips the library`() {
        val rails = source("src/main/java/dev/mirutime/app/design/components/ThemePicker.kt")

        assertTrue(rails.contains("applyPreset(context, preset)"))
        assertEquals(false, rails.contains("importPresetBackground("))
        assertTrue(rails.contains("R.string.theme_preset_item_fmt"))

        val tile = rails.substringAfter("private fun PresetTile(").substringBefore("@Composable")
        assertEquals(false, tile.contains("Icons."))
        val placeholder = rails.substringAfter("private fun PresetPlaceholderTile(")
            .substringBefore("@Composable")
        assertEquals(false, placeholder.contains("Icons."))

        val activity = source("src/main/java/dev/mirutime/app/MainActivity.kt")
        assertTrue(activity.contains("isPresetBackgroundPath(applicationContext, source)"))
        val storage = source("src/main/java/dev/mirutime/app/design/ThemeStorage.kt")
        assertTrue(storage.contains("filterNot { isPresetBackgroundPath(context, it) }"))
    }

    @Test
    fun `preset and local labels are separate strings in both locales`() {
        val en = resource("ui-kit/src/main/res/values/strings.xml")
        val ru = resource("ui-kit/src/main/res/values-ru/strings.xml")

        assertTrue(en.contains("name=\"theme_preset_item_fmt\""))
        assertTrue(en.contains("name=\"theme_custom_item_fmt\""))
        assertTrue(ru.contains("name=\"theme_preset_item_fmt\""))
        assertTrue(ru.contains("name=\"theme_custom_item_fmt\""))
        assertEquals(false, en.contains("name=\"theme_preset_item_fmt\">Background"))
        assertEquals(false, ru.contains("name=\"theme_preset_item_fmt\">Фон"))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    private fun resource(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).first { it.isFile }.readText()
}
