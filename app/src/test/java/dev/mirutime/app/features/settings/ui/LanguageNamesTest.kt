package dev.mirutime.app.features.settings.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.mirutime.app.features.settings.data.AppSettings

class LanguageNamesTest {

    /**
     * Language names in the translation pickers must follow the language the
     * interface is in, not the device locale: `Locale.getDefault()` ignores the
     * in-app override.
     */
    @Test
    fun `language names follow the in-app choice`() {
        val saved = AppSettings.appLanguage
        try {
            AppSettings.appLanguage = "de"
            assertEquals("Französisch", uiLanguageName("fr"))
            AppSettings.appLanguage = "es"
            assertEquals("Francés", uiLanguageName("fr"))
        } finally {
            AppSettings.appLanguage = saved
        }
    }

    @Test
    fun `system follows the device locale`() {
        val saved = AppSettings.appLanguage
        try {
            AppSettings.appLanguage = "system"
            val device = Locale.getDefault()
            val expected = Locale.forLanguageTag("fr").getDisplayLanguage(device)
                .replaceFirstChar { it.titlecase(device) }
            assertEquals(expected, uiLanguageName("fr"))
        } finally {
            AppSettings.appLanguage = saved
        }
    }
}
