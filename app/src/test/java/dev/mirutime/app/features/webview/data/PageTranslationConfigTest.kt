package dev.mirutime.app.features.webview.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.NovelPrompt
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.translator.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTranslationConfigTest {

    private fun resolve(
        novel: TranslationConfig? = null,
        plugin: TranslationConfig? = null,
        source: String = "en",
        target: String = "",
        provider: String = "GOOGLE_PA",
        device: String = "ru",
        activePrompt: String = "",
        novelPrompt: NovelPrompt? = null,
    ): PageTranslationConfig? = resolvePageTranslation(
        novel = novel,
        plugin = plugin,
        globalSource = source,
        globalTarget = target,
        globalProvider = provider,
        deviceLanguage = device,
        activePrompt = activePrompt,
        novelPrompt = novelPrompt,
    )

    @Test
    fun explicitTargetWins() {
        val config = resolve(target = "ja")

        assertEquals("en", config?.source?.value)
        assertEquals("ja", config?.target?.value)
        assertEquals(ProviderId.GOOGLE_PA, config?.provider)
        assertNull(config?.prompt)
    }

    @Test
    fun blankOrAutoSourceDetectsLanguage() {
        assertNull(resolve(source = "").let { it?.source })
        assertNull(resolve(source = "auto").let { it?.source })
        assertEquals("ru", resolve(source = "auto", target = "ru")?.target?.value)
    }

    @Test
    fun blankTargetFollowsDeviceLanguage() {
        assertEquals("de", resolve(target = "", device = "de").let { it?.target?.value })
        // A region tag is reduced to the supported base language.
        assertEquals("pt", resolve(target = "", device = "pt-BR").let { it?.target?.value })
    }

    @Test
    fun unsupportedDeviceFallsBackToEnglish() {
        assertEquals("en", resolve(source = "zh", target = "", device = "nb").let { it?.target?.value })
        // English page + unsupported device language leaves no useful target.
        assertNull(resolve(source = "en", target = "", device = "nb"))
    }

    @Test
    fun sameTargetFallsBackToEnglish() {
        assertEquals("en", resolve(source = "ru", target = "ru").let { it?.target?.value })
    }

    @Test
    fun englishSourceAndTargetAskForAnotherLanguage() {
        assertEquals("ru", resolve(source = "en", target = "", device = "ru").let { it?.target?.value })
        // Same base tag counts as the same language.
        assertEquals("ru", resolve(source = "en", target = "en-US", device = "ru").let { it?.target?.value })
    }

    @Test
    fun noUsableTargetWhenEverythingIsEnglish() {
        assertNull(resolve(source = "en", target = "en", device = "en"))
    }

    @Test
    fun unknownProviderFallsBackToGoogleFree() {
        val config = resolve(provider = "SOMETHING_ELSE")

        assertEquals(ProviderId.GOOGLE_FREE, config?.provider)
    }

    @Test
    fun providerParsingIsCaseInsensitive() {
        assertEquals(ProviderId.DEEPL, resolve(provider = "deepl")?.provider)
    }

    @Test
    fun enabledNovelWinsOverPlugin() {
        val config = resolve(
            novel = TranslationConfig(
                enabled = true,
                source = "en",
                target = "ja",
                provider = "GEMINI",
            ),
            plugin = TranslationConfig(
                enabled = true,
                source = "zh",
                target = "ru",
                provider = "DEEPL",
            ),
        )

        assertEquals("en", config?.source?.value)
        assertEquals("ja", config?.target?.value)
        assertEquals(ProviderId.GEMINI, config?.provider)
    }

    @Test
    fun disabledNovelDoesNotLeakItsPairOrProvider() {
        val config = resolve(
            novel = TranslationConfig(
                enabled = false,
                source = "fr",
                target = "de",
                provider = "ANTHROPIC",
            ),
            plugin = TranslationConfig(
                enabled = true,
                source = "zh",
                target = "ru",
                provider = "DEEPL",
            ),
        )

        assertEquals("zh", config?.source?.value)
        assertEquals("ru", config?.target?.value)
        assertEquals(ProviderId.DEEPL, config?.provider)
    }

    @Test
    fun disabledPluginDoesNotLeakItsPairOrProvider() {
        val config = resolve(
            plugin = TranslationConfig(
                enabled = false,
                source = "ja",
                target = "de",
                provider = "DEEPL",
            ),
            target = "ru",
        )

        assertEquals("en", config?.source?.value)
        assertEquals("ru", config?.target?.value)
        assertEquals(ProviderId.GOOGLE_PA, config?.provider)
    }

    @Test
    fun novelBlankSideInheritsFromTheNextEnabledLevel() {
        val config = resolve(
            novel = TranslationConfig(enabled = true, source = "ja"),
            plugin = TranslationConfig(
                enabled = true,
                source = "zh",
                target = "ko",
                provider = "DEEPL",
            ),
        )

        assertEquals("ja", config?.source?.value)
        assertEquals("ko", config?.target?.value)
        assertEquals(ProviderId.DEEPL, config?.provider)
    }

    @Test
    fun novelOnlyTargetInheritsGlobalSource() {
        val config = resolve(
            novel = TranslationConfig(enabled = true, target = "ru"),
        )

        assertEquals("en", config?.source?.value)
        assertEquals("ru", config?.target?.value)
    }

    @Test
    fun autoNovelSourceIsDetectedAtTranslationTime() {
        val config = resolve(
            novel = TranslationConfig(enabled = true, source = "auto", target = "ru"),
        )

        assertNull(config?.source)
        assertEquals("ru", config?.target?.value)
    }

    @Test
    fun unparseableCascadedProviderFallsBackToGoogleFree() {
        val config = resolve(
            novel = TranslationConfig(enabled = true, target = "ru", provider = "BOGUS"),
            plugin = TranslationConfig(enabled = true, provider = "DEEPL"),
        )

        assertEquals(ProviderId.GOOGLE_FREE, config?.provider)
    }

    @Test
    fun novelPromptReplacesTheGlobalOne() {
        val config = resolve(
            activePrompt = "GLOBAL PROMPT",
            novelPrompt = NovelPrompt(
                bookUrl = "fake://source/book",
                title = "Book",
                prompt = "NOVEL PROMPT",
                append = false,
            ),
        )

        assertEquals("NOVEL PROMPT", config?.prompt?.text)
    }

    @Test
    fun appendPromptCombinesGlobalAndNovel() {
        val config = resolve(
            activePrompt = "GLOBAL PROMPT",
            novelPrompt = NovelPrompt(
                bookUrl = "fake://source/book",
                title = "Book",
                prompt = "NOVEL PROMPT",
                append = true,
            ),
        )

        assertEquals("GLOBAL PROMPT\n\nNOVEL PROMPT", config?.prompt?.text)
    }

    @Test
    fun activePromptIsUsedWhenTheNovelHasNone() {
        val config = resolve(activePrompt = "  GLOBAL PROMPT  ")

        assertEquals("GLOBAL PROMPT", config?.prompt?.text)
    }

    @Test
    fun blankPromptsProduceNull() {
        assertNull(
            resolve(
                novelPrompt = NovelPrompt(
                    bookUrl = "fake://source/book",
                    title = "Book",
                    prompt = "   ",
                    append = true,
                ),
            )?.prompt,
        )
    }

    @Test
    fun explicitSourceIdWinsOverTheRealUrlHost() {
        val key = "chikari"
        val previous = AppSettings.pluginTranslation[key]
        try {
            AppSettings.pluginTranslation[key] = TranslationConfig(
                enabled = true,
                source = "ja",
                target = "ru",
                provider = "DEEPL",
            )

            val config = resolvePageTranslationFor(
                bookUrl = "https://chikari.moe/series/the-crazy-villain-regains-his-sanity",
                sourceId = key,
                deviceLanguage = "ru",
            )

            assertEquals("ja", config?.source?.value)
            assertEquals("ru", config?.target?.value)
            assertEquals(ProviderId.DEEPL, config?.provider)
        } finally {
            if (previous == null) {
                AppSettings.pluginTranslation.remove(key)
            } else {
                AppSettings.pluginTranslation[key] = previous
            }
        }
    }
}
