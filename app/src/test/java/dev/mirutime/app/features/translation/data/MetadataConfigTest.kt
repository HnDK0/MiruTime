package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.translator.ProviderId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataConfigTest {
    private val bookUrl = "fake://lib/one"

    @After
    fun cleanup() {
        AppSettings.novelTranslation.remove(bookUrl)
    }

    private fun store(provider: String, full: Boolean = true): MetadataConfig? {
        AppSettings.novelTranslation[bookUrl] = TranslationConfig(
            enabled = true,
            source = "en",
            target = "ru",
            provider = provider,
            full = full,
        )
        return metadataConfigFor(bookUrl)
    }

    // The free-provider guarantee lives in the translator library; the app
    // passes the user's selection through untouched.
    @Test
    fun `selected provider is passed through`() {
        assertEquals(ProviderId.GEMINI, store("GEMINI")?.provider)
        assertEquals(ProviderId.GOOGLE_PA, store("GOOGLE_PA")?.provider)
        assertNotNull(store("DEEPL"))
    }

    @Test
    fun `standard scope also yields a metadata config`() {
        // Chapter titles translate whenever translation is on with a pair, not
        // only for a FULL override (FR-112).
        assertNotNull(store("GEMINI", full = false))
    }
}