package dev.mirutime.app.features.settings.data

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
    }

    @Test
    fun `only resource-backed app languages are offered`() {
        assertEquals(listOf("en", "ru"), SettingsLanguages.app)
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
