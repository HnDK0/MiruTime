package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPresentationTest {
    private val bookUrl = "fake://lib/one"
    private val pluginId = "lib"

    @After
    fun cleanup() {
        AppSettings.novelTranslation.remove(bookUrl)
        AppSettings.pluginTranslation.remove(pluginId)
    }

    private fun config(
        enabled: Boolean = true,
        hideLibrary: Boolean = false,
        hideHistory: Boolean = false,
        hideCatalog: Boolean = false,
        hideSearch: Boolean = false,
        chapterTitles: Boolean = true,
    ) = TranslationConfig(
        enabled = enabled,
        source = "en",
        target = "ru",
        full = true,
        hideLibrary = hideLibrary,
        hideHistory = hideHistory,
        hideCatalog = hideCatalog,
        hideSearch = hideSearch,
        chapterTitles = chapterTitles,
    )

    @Test
    fun `no config shows every title and chapter titles`() {
        val presentation = translationPresentationFor(bookUrl)

        assertTrue(presentation.chapterTitles)
        TitleSurface.entries.forEach { assertTrue(presentation.showsTitle(it)) }
    }

    @Test
    fun `enabled novel decides the hidden surfaces`() {
        AppSettings.novelTranslation[bookUrl] = config(
            hideLibrary = true,
            hideSearch = true,
            chapterTitles = false,
        )

        val presentation = translationPresentationFor(bookUrl)

        assertFalse(presentation.chapterTitles)
        assertFalse(presentation.showsTitle(TitleSurface.Library))
        assertFalse(presentation.showsTitle(TitleSurface.Search))
        assertTrue(presentation.showsTitle(TitleSurface.History))
        assertTrue(presentation.showsTitle(TitleSurface.Catalog))
    }

    @Test
    fun `disabled novel never leaks its flags`() {
        AppSettings.novelTranslation[bookUrl] = config(enabled = false, hideLibrary = true)
        AppSettings.pluginTranslation[pluginId] = config(hideHistory = true)

        val presentation = translationPresentationFor(bookUrl)

        assertTrue(presentation.showsTitle(TitleSurface.Library))
        assertFalse(presentation.showsTitle(TitleSurface.History))
    }

    @Test
    fun `enabled novel wins over an enabled plugin`() {
        AppSettings.novelTranslation[bookUrl] = config(hideCatalog = true)
        AppSettings.pluginTranslation[pluginId] = config(hideLibrary = true, chapterTitles = false)

        val presentation = translationPresentationFor(bookUrl)

        assertTrue(presentation.showsTitle(TitleSurface.Library))
        assertFalse(presentation.showsTitle(TitleSurface.Catalog))
        assertEquals(true, presentation.chapterTitles)
    }
}
