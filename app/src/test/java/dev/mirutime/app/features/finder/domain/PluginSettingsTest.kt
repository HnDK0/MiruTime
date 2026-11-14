package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {

    // The flat shape the core produces from a Lua getSettingsSchema().
    private fun setting(
        key: String,
        label: String? = key,
        type: String? = "select",
        current: String? = null,
        options: List<Pair<String, String?>> = emptyList(),
    ): Map<String, String> = buildMap {
        put("key", key)
        type?.let { put("type", it) }
        label?.let { put("label", it) }
        current?.let { put("current", it) }
        put("options.count", options.size.toString())
        options.forEachIndexed { index, (value, optionLabel) ->
            put("options.$index.value", value)
            optionLabel?.let { put("options.$index.label", it) }
        }
    }

    @Test
    fun `ranobes full and lite select keeps key label current and options in order`() {
        val parsed = parsePluginSettings(
            listOf(
                setting(
                    key = "ranobes_chapter_list",
                    label = "Chapter list (re-add books after changing)",
                    current = "full",
                    options = listOf(
                        "full" to "Full — real titles (many requests on long novels)",
                        "lite" to "Lite — numbered chapters, opened on demand",
                    ),
                ),
            ),
        )

        val ranobes = parsed.single()
        assertEquals("ranobes_chapter_list", ranobes.key)
        assertEquals("Chapter list (re-add books after changing)", ranobes.label)
        assertEquals("full", ranobes.current)
        assertEquals(
            listOf(
                PluginSettingOption("full", "Full — real titles (many requests on long novels)"),
                PluginSettingOption("lite", "Lite — numbered chapters, opened on demand"),
            ),
            ranobes.options,
        )
    }

    @Test
    fun `several settings keep the order the plugin declared`() {
        val parsed = parsePluginSettings(
            listOf(
                setting("wtrlab_mode", options = listOf("ai" to "AI", "raw" to "Raw")),
                setting("wtrlab_chapter_delay", options = listOf("0" to "None", "2000" to "2 seconds")),
            ),
        )

        assertEquals(listOf("wtrlab_mode", "wtrlab_chapter_delay"), parsed.map { it.key })
    }

    // novel543 ships a 15-language picker; the sheet must hand all of them to
    // the choice sheet, not truncate at the chip row.
    @Test
    fun `a fifteen-option language picker survives the flat shape`() {
        val languages = listOf(
            "en" to "English",
            "es" to "Español",
            "pt" to "Português",
            "ru" to "Русский",
            "fr" to "Français",
            "de" to "Deutsch",
            "tr" to "Türkçe",
            "ar" to "العربية",
            "id" to "Bahasa Indonesia",
            "th" to "ไทย",
            "vi" to "Tiếng Việt",
            "ja" to "日本語",
            "ko" to "한국어",
            "hi" to "हिन्दी",
            "ur" to "اردو",
        )
        val parsed = parsePluginSettings(
            listOf(setting("novel543_tlang", current = "en", options = languages)),
        )

        assertEquals(languages.map { it.first }, parsed.single().options.map { it.key })
        assertFalse(parsed.single().options.fitsChipRow())
    }

    @Test
    fun `missing type means select and missing label falls back to the key`() {
        val parsed = parsePluginSettings(
            listOf(setting("mode", label = null, type = null, options = listOf("a" to "A"))),
        )

        assertEquals("mode", parsed.single().label)
        assertEquals("", parsed.single().current)
    }

    @Test
    fun `unknown types, keyless entries and entries without options are dropped`() {
        val parsed = parsePluginSettings(
            listOf(
                setting("token", type = "text", options = listOf("a" to "A")),
                setting("   ", options = listOf("a" to "A")),
                setting("empty", options = emptyList()),
                mapOf("key" to "no-count", "type" to "select"),
                setting("kept", options = listOf("a" to "A")),
            ),
        )

        assertEquals(listOf("kept"), parsed.map { it.key })
    }

    @Test
    fun `option without a value is dropped and a missing label falls back to the value`() {
        val parsed = parsePluginSettings(
            listOf(
                setting(
                    key = "mode",
                    options = listOf("" to "Default", "   " to "Blank", "fast" to null, "slow" to "Slow"),
                ),
            ),
        )

        assertEquals(
            listOf(PluginSettingOption("fast", "fast"), PluginSettingOption("slow", "Slow")),
            parsed.single().options,
        )
    }

    @Test
    fun `a setting whose options are all valueless is dropped`() {
        val parsed = parsePluginSettings(
            listOf(setting("mode", options = listOf("" to "Default", " " to "Other"))),
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `duplicate setting keys and duplicate option values keep the first`() {
        val parsed = parsePluginSettings(
            listOf(
                setting("mode", label = "First", options = listOf("a" to "A", "a" to "Again", "b" to "B")),
                setting("mode", label = "Second", options = listOf("z" to "Z")),
            ),
        )

        assertEquals(1, parsed.size)
        assertEquals("First", parsed.single().label)
        assertEquals(listOf("a", "b"), parsed.single().options.map { it.key })
        assertEquals("A", parsed.single().options.first().label)
    }

    @Test
    fun `the key is the plugin's preference name and is never trimmed`() {
        val parsed = parsePluginSettings(
            listOf(setting(" spaced ", options = listOf("a" to "A"))),
        )

        assertEquals(" spaced ", parsed.single().key)
    }

    @Test
    fun `an empty schema parses to no settings`() {
        assertEquals(emptyList<PluginSettingSpec>(), parsePluginSettings(emptyList()))
    }

    // ── chip row vs choice sheet, on the labels real plugins ship ─────────────

    @Test
    fun `short labels of three or fewer options are chips`() {
        assertTrue(
            listOf(PluginSettingOption("novel", "Novels"), PluginSettingOption("fanfic", "Fanfic"))
                .fitsChipRow(),
        )
        // tomatomtl: three options, the longest is 23 characters.
        assertTrue(
            listOf(
                PluginSettingOption("raw", "Raw (Chinese)"),
                PluginSettingOption("google", "Google Translate (fast)"),
                PluginSettingOption("cina", "CinaNMT"),
            ).fitsChipRow(),
        )
        // novel543 chapter-title toggle.
        assertTrue(
            listOf(PluginSettingOption("1", "On (開)"), PluginSettingOption("0", "Off (關)"))
                .fitsChipRow(),
        )
    }

    @Test
    fun `sentence labels go to the choice sheet even when there are only two`() {
        // ranobes: both labels are full sentences.
        assertFalse(
            listOf(
                PluginSettingOption("full", "Full — real titles (many requests on long novels)"),
                PluginSettingOption("lite", "Lite — numbered chapters, opened on demand"),
            ).fitsChipRow(),
        )
        // novel543 mode picker.
        assertFalse(
            listOf(
                PluginSettingOption("raw", "Raw (原文) — Chinese UI, no translation"),
                PluginSettingOption(
                    "translate",
                    "Translator (翻譯模式) — translate everything except chapter text",
                ),
            ).fitsChipRow(),
        )
        // wtrlab's delay picker: four options, choice sheet regardless of length.
        assertFalse(
            listOf(
                PluginSettingOption("0", "None"),
                PluginSettingOption("2000", "2 seconds"),
                PluginSettingOption("4000", "4 seconds"),
                PluginSettingOption("6000", "6 seconds"),
            ).fitsChipRow(),
        )
    }

    @Test
    fun `the label threshold is inclusive at the boundary`() {
        val exactly = "x".repeat(PluginSettingChipLabelMax)
        val over = "x".repeat(PluginSettingChipLabelMax + 1)

        assertTrue(listOf(PluginSettingOption("a", exactly)).fitsChipRow())
        assertFalse(listOf(PluginSettingOption("a", over)).fitsChipRow())
    }
}
