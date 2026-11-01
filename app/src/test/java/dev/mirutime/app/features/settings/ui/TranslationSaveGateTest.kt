package dev.mirutime.app.features.settings.ui

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Save button in every translation editor is enabled by this gate:
// a disabled config must always be savable (the user can just store "off" or
// pick a provider), while an enabled one needs a resolved, non-identical pair.
class TranslationSaveGateTest {

    @Test
    fun disabledConfigIsSavableWithoutPair() {
        val previousSource = AppSettings.globalSource
        val previousTarget = AppSettings.globalTarget
        AppSettings.globalSource = "en"
        AppSettings.globalTarget = ""
        try {
            assertTrue(isTranslationSaveEnabled(TranslationConfig()))
            assertTrue(
                isTranslationSaveEnabled(
                    TranslationConfig(enabled = false, provider = "GEMINI"),
                ),
            )
        } finally {
            AppSettings.globalSource = previousSource
            AppSettings.globalTarget = previousTarget
        }
    }

    @Test
    fun enabledConfigRequiresExplicitPair() {
        val previousSource = AppSettings.globalSource
        val previousTarget = AppSettings.globalTarget
        AppSettings.globalSource = "en"
        AppSettings.globalTarget = ""
        try {
            assertFalse(isTranslationSaveEnabled(TranslationConfig(enabled = true)))
            assertTrue(
                isTranslationSaveEnabled(
                    TranslationConfig(enabled = true, source = "en", target = "ru"),
                ),
            )
            assertFalse(
                isTranslationSaveEnabled(
                    TranslationConfig(enabled = true, source = "en", target = "en"),
                ),
            )
        } finally {
            AppSettings.globalSource = previousSource
            AppSettings.globalTarget = previousTarget
        }
    }

    @Test
    fun enabledConfigMayInheritACompleteGlobalPair() {
        val previousSource = AppSettings.globalSource
        val previousTarget = AppSettings.globalTarget
        AppSettings.globalSource = "en"
        AppSettings.globalTarget = "ru"
        try {
            assertTrue(isTranslationSaveEnabled(TranslationConfig(enabled = true)))
        } finally {
            AppSettings.globalSource = previousSource
            AppSettings.globalTarget = previousTarget
        }
    }
}
