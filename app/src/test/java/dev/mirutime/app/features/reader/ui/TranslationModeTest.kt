package dev.mirutime.app.features.reader.ui

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.feature.reader.api.ReaderChapterTranslation
import dev.mirutime.feature.reader.api.ReaderTranslationStatus
import dev.mirutime.app.features.translation.data.TranslationMode
import dev.mirutime.feature.reader.api.ReaderTranslationMode
import dev.mirutime.feature.reader.api.visibleTranslations
import dev.mirutime.app.features.translation.data.translationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslationModeTest {
    @Before
    fun resetGlobalMode() {
        AppSettings.translationGlobalMode = false
    }

    @Test
    fun `off everywhere means no badge`() {
        val mode = translationMode(
            novel = TranslationConfig(),
            plugin = null,
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertNull(mode)
    }

    @Test
    fun `novel pair wins over plugin and global`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = true, source = "ja", target = "ru"),
            plugin = TranslationConfig(enabled = true, source = "zh", target = "en"),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "de",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("ja", mode?.source)
        assertEquals("ru", mode?.target)
        assertTrue(mode?.hasPair == true)
    }

    @Test
    fun `blank novel fields inherit the global values`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = TranslationConfig(enabled = true),
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GEMINI",
        )

        assertEquals("en", mode?.source)
        assertEquals("ru", mode?.target)
        assertEquals("GEMINI", mode?.provider)
        assertTrue(mode?.hasPair == true)
    }

    @Test
    fun `global mode without a target is on but has no pair`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("en", mode?.source)
        assertTrue(mode?.hasPair == false)
    }

    @Test
    fun `same source and target is not a pair`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "en",
            globalProvider = "GOOGLE_PA",
        )

        assertTrue(mode?.hasPair == false)
    }

    @Test
    fun `enabled plugin pair is used when the novel is off`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = false, source = "fr", target = "de"),
            plugin = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ko",
                provider = "DEEPL",
            ),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("ja", mode?.source)
        assertEquals("ko", mode?.target)
        assertEquals("DEEPL", mode?.provider)
    }

    @Test
    fun `disabled plugin is ignored`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = null,
            plugin = TranslationConfig(
                enabled = false,
                source = "ja",
                target = "ko",
                provider = "DEEPL",
            ),
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("en", mode?.source)
        assertEquals("ru", mode?.target)
        assertEquals("GOOGLE_PA", mode?.provider)
    }

    @Test
    fun `enabled novel provider wins over plugin and global`() {
        val mode = translationMode(
            novel = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "GEMINI",
            ),
            plugin = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "DEEPL",
            ),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "de",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("GEMINI", mode?.provider)
    }

    @Test
    fun `disabled novel provider does not leak`() {
        val toPlugin = translationMode(
            novel = TranslationConfig(
                enabled = false,
                source = "ja",
                target = "ru",
                provider = "GEMINI",
            ),
            plugin = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "DEEPL",
            ),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "de",
            globalProvider = "GOOGLE_PA",
        )
        assertEquals("DEEPL", toPlugin?.provider)

        val toGlobal = translationMode(
            novel = TranslationConfig(enabled = false, provider = "GEMINI"),
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )
        assertEquals("GOOGLE_PA", toGlobal?.provider)
    }

    @Test
    fun `blank novel provider falls through to the enabled plugin`() {
        val mode = translationMode(
            novel = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "   ",
            ),
            plugin = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "DEEPL",
            ),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "de",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("DEEPL", mode?.provider)
    }

    @Test
    fun `no novel or plugin provider falls back to global`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = true, source = "ja", target = "ru"),
            plugin = null,
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "de",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("GOOGLE_PA", mode?.provider)
    }

    @Test
    fun `global pair applies only in global mode`() {
        AppSettings.translationGlobalMode = true
        val inMode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )
        assertEquals("en", inMode?.source)
        assertEquals("ru", inMode?.target)

        AppSettings.translationGlobalMode = false
        val outsideMode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )
        assertEquals("", outsideMode?.source)
        assertEquals("", outsideMode?.target)
        assertTrue(outsideMode?.hasPair == false)
    }

    @Test
    fun `global mode needs the global switch to be on`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertNull(mode)
    }

    @Test
    fun `cached translations stay hidden while translation is off`() {
        val cached = mapOf(
            "book#chapter=1" to ReaderChapterTranslation(
                status = ReaderTranslationStatus.Ready,
                translations = mapOf(0 to "перевод"),
                configKey = "key",
                bookUrl = "book",
            ),
        )

        assertTrue(visibleTranslations(null, "book", cached).isEmpty())
        assertTrue(
            visibleTranslations(
                ReaderTranslationMode(source = "en", target = "", provider = "GOOGLE_PA"),
                "book",
                cached,
            ).isEmpty(),
        )
        assertEquals(
            cached,
            visibleTranslations(
                ReaderTranslationMode(source = "en", target = "ru", provider = "GOOGLE_PA"),
                "book",
                cached,
            ),
        )
    }

    @Test
    fun `translation from another book is never painted`() {
        val foreign = mapOf(
            "shared-chapter" to ReaderChapterTranslation(
                status = ReaderTranslationStatus.Ready,
                translations = mapOf(0 to "чужой перевод"),
                configKey = "key",
                bookUrl = "fake://a/other",
            ),
        )

        assertTrue(
            visibleTranslations(
                ReaderTranslationMode(source = "en", target = "ru", provider = "GOOGLE_PA"),
                "fake://a/current",
                foreign,
            ).isEmpty(),
        )
    }

    @Test
    fun `full scope follows the enabled novel and ignores a disabled plugin`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = true, source = "en", target = "ru", full = true),
            plugin = TranslationConfig(enabled = true, full = false),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals(true, mode?.full)
    }

    @Test
    fun `full scope falls back to the enabled plugin`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = false, full = true),
            plugin = TranslationConfig(enabled = true, source = "en", target = "ru", full = true),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals(true, mode?.full)
    }

    @Test
    fun `enabled novel without full blocks the plugin flag`() {
        val mode = translationMode(
            novel = TranslationConfig(enabled = true, source = "en", target = "ru", full = false),
            plugin = TranslationConfig(enabled = true, full = true),
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals(false, mode?.full)
    }

    @Test
    fun `global mode never enables full scope`() {
        AppSettings.translationGlobalMode = true
        val mode = translationMode(
            novel = null,
            plugin = null,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
            globalProvider = "GOOGLE_PA",
        )

        assertEquals(false, mode?.full)
    }
}
