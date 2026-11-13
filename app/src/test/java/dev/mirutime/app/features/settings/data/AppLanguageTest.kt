package dev.mirutime.app.features.settings.data

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `supported codes map to real locales`() {
        assertEquals("ru", AppLanguage.localeFor("ru")?.language)
        assertEquals("en", AppLanguage.localeFor("en")?.language)
        assertEquals("ru", AppLanguage.localeFor("ru-RU")?.language)
        // The legacy code "in" is normalized to "id" on modern JDKs; older
        // Android keeps "in". The platform resolves values-in from either.
        assertTrue(AppLanguage.localeFor("in")?.language in setOf("in", "id"))
    }

    @Test
    fun `only resource-backed app languages are offered`() {
        assertEquals(
            listOf("en", "ru", "es", "pt", "in", "vi", "fr", "de", "it", "tr"),
            SettingsLanguages.app,
        )
    }

    /**
     * Every offered code must have a values-XX folder, otherwise the picker
     * silently switches to English. "en" lives in the default values/ folder.
     */
    @Test
    fun `every offered app language has string resources`() {
        val res = File("src/main/res")
        val missing = SettingsLanguages.app.filter { code ->
            code != "en" && !File(res, "values-$code").isDirectory
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `system and blank codes follow the system locale`() {
        assertNull(AppLanguage.localeFor(AppLanguage.System))
        assertNull(AppLanguage.localeFor(""))
        assertNull(AppLanguage.localeFor("  "))
    }

    @Test
    fun `recreate only when the requested language differs`() {
        assertTrue(AppLanguage.needsRecreate(Locale.ENGLISH, "ru"))
        assertFalse(AppLanguage.needsRecreate(Locale.forLanguageTag("ru-RU"), "ru"))
        assertFalse(AppLanguage.needsRecreate(Locale.getDefault(), AppLanguage.System))
    }

    @Test
    fun `locale display name is localized by the chosen locale`() {
        assertEquals(
            "Russian",
            AppLanguage.localeFor("ru")?.getDisplayLanguage(Locale.ENGLISH),
        )
        assertEquals(
            "английский",
            AppLanguage.localeFor("en")?.getDisplayLanguage(Locale.forLanguageTag("ru"))?.lowercase(),
        )
    }
}
