package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.settings.data.TranslationPair
import dev.mirutime.translator.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TranslationConfigResolverTest {

    private fun configs(snapshot: TranslationSettingsSnapshot = TranslationSettingsSnapshot()) =
        resolveProviderConfigs(snapshot)

    private fun config(
        provider: ProviderId,
        snapshot: TranslationSettingsSnapshot = TranslationSettingsSnapshot(),
    ) = configs(snapshot).single { it.provider == provider }

    @Test
    fun `all ten providers are mapped exactly once`() {
        assertEquals(ProviderId.entries.toList(), configs().map { it.provider })
    }

    @Test
    fun `api keys are trimmed deduplicated and blank lines dropped`() {
        val google = config(
            ProviderId.GOOGLE_PA,
            TranslationSettingsSnapshot(googlePaKeys = " key-a \n\nkey-b\n key-a \n"),
        )

        assertEquals(listOf("key-a", "key-b"), google.apiKeys)
        assertTrue(config(ProviderId.GOOGLE_FREE).apiKeys.isEmpty())
        assertTrue(config(ProviderId.YANDEX_FREE).apiKeys.isEmpty())
    }

    @Test
    fun `models and base urls follow settings`() {
        val snapshot = TranslationSettingsSnapshot(
            geminiModel = " gemini-test ",
            openAiKeys = "sk-openai",
            openAiModel = " gpt-test ",
            openAiBaseUrl = "",
            openAiCompatibleKeys = "sk-compat",
            openAiCompatibleModel = " ",
            openAiCompatibleBaseUrl = " https://llm.example/v1 ",
            anthropicModel = "claude-test",
            yandexCloudFolderId = " folder-1 ",
        )

        assertEquals("gemini-test", config(ProviderId.GEMINI, snapshot).model)
        assertEquals("gpt-test", config(ProviderId.OPENAI, snapshot).model)
        assertEquals("https://api.openai.com", config(ProviderId.OPENAI, snapshot).baseUrl)
        assertNull(config(ProviderId.OPENAI_COMPATIBLE, snapshot).model)
        assertEquals("https://llm.example/v1", config(ProviderId.OPENAI_COMPATIBLE, snapshot).baseUrl)
        assertEquals("claude-test", config(ProviderId.ANTHROPIC, snapshot).model)
        assertEquals("folder-1", config(ProviderId.YANDEX_CLOUD, snapshot).folderId)
    }

    @Test
    fun `zero limits map to contract defaults`() {
        val defaults = configs(TranslationSettingsSnapshot(maxOutputTokens = 0, maxCharsPerRequest = 0))
        assertTrue(defaults.all { it.maxOutputTokens == null })
        assertTrue(defaults.all { it.maxCharsPerRequest == null })

        val explicit = configs(TranslationSettingsSnapshot(maxOutputTokens = 1024, maxCharsPerRequest = 500))
        assertTrue(explicit.all { it.maxOutputTokens == 1024 })
        assertTrue(explicit.all { it.maxCharsPerRequest == 500 })
    }

    @Test
    fun `timeout and attempt count map to provider config`() {
        val gemini = config(
            ProviderId.GEMINI,
            TranslationSettingsSnapshot(timeoutSeconds = 45, maxAttempts = 3),
        )

        assertEquals(45.seconds, gemini.timeout)
        assertEquals(3, gemini.maxAttempts)
    }

    @Test
    fun `concurrency is clamped to provider limits`() {
        assertEquals(32, config(ProviderId.GOOGLE_PA, TranslationSettingsSnapshot(chunkConcurrency = 99)).chunkConcurrency)
        assertEquals(1, config(ProviderId.GOOGLE_FREE, TranslationSettingsSnapshot(chunkConcurrency = 0)).chunkConcurrency)
        assertEquals(16, config(ProviderId.YANDEX_PA, TranslationSettingsSnapshot(chunkConcurrency = 32)).chunkConcurrency)
        assertEquals(1, config(ProviderId.YANDEX_PA, TranslationSettingsSnapshot(chunkConcurrency = 0)).chunkConcurrency)
    }

    @Test
    fun `json schema stays off`() {
        assertTrue(configs().none { it.useJsonSchema })
    }

    @Test
    fun `enabled is an OR of novel plugin and global levels`() {
        assertFalse(resolveTranslationEnabled(null, null, globalEnabled = false))
        assertTrue(resolveTranslationEnabled(TranslationConfig(enabled = true), null, globalEnabled = false))
        assertTrue(resolveTranslationEnabled(null, TranslationConfig(enabled = true), globalEnabled = false))
        assertTrue(resolveTranslationEnabled(null, null, globalEnabled = true))
        assertFalse(
            resolveTranslationEnabled(
                TranslationConfig(enabled = false),
                TranslationConfig(enabled = false),
                globalEnabled = false,
            ),
        )
    }

    @Test
    fun `enabled novel pair wins over plugin and global`() {
        val pair = resolveTranslationPair(
            novel = TranslationConfig(enabled = true, source = "fr", target = "de"),
            plugin = TranslationConfig(enabled = true, source = "ja", target = "ko"),
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("fr", "de"), pair)
    }

    @Test
    fun `disabled novel does not leak its pair`() {
        val pair = resolveTranslationPair(
            novel = TranslationConfig(enabled = false, source = "fr", target = "de"),
            plugin = TranslationConfig(enabled = true, source = "ja", target = "ko"),
            globalMode = false,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("ja", "ko"), pair)
    }

    @Test
    fun `enabled plugin wins over global`() {
        val pair = resolveTranslationPair(
            novel = null,
            plugin = TranslationConfig(enabled = true, source = "ja", target = "ko"),
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("ja", "ko"), pair)
    }

    @Test
    fun `disabled plugin does not leak and global pair applies`() {
        val pair = resolveTranslationPair(
            novel = null,
            plugin = TranslationConfig(enabled = false, source = "ja", target = "ko"),
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("en", "ru"), pair)
    }

    @Test
    fun `global pair applies only in global mode with global enabled`() {
        assertEquals(
            TranslationPair("en", "ru"),
            resolveTranslationPair(null, null, true, true, "en", "ru"),
        )
        assertEquals(
            TranslationPair("", ""),
            resolveTranslationPair(null, null, true, false, "en", "ru"),
        )
        assertEquals(
            TranslationPair("", ""),
            resolveTranslationPair(null, null, false, true, "en", "ru"),
        )
    }

    @Test
    fun `enabled novel without its own pair inherits from global`() {
        val pair = resolveTranslationPair(
            novel = TranslationConfig(enabled = true),
            plugin = null,
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("en", "ru"), pair)
    }

    @Test
    fun `enabled novel partial pair inherits the missing side from global`() {
        val pair = resolveTranslationPair(
            novel = TranslationConfig(enabled = true, target = "ru"),
            plugin = null,
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("en", "ru"), pair)
    }

    @Test
    fun `partial pair inherits global languages even when global mode is off`() {
        val pair = resolveTranslationPair(
            novel = TranslationConfig(enabled = true, target = "ru"),
            plugin = null,
            globalMode = false,
            globalEnabled = false,
            globalSource = "en",
            globalTarget = "",
        )

        assertEquals(TranslationPair("en", "ru"), pair)
    }

    @Test
    fun `enabled plugin partial pair inherits the missing side from global`() {
        val pair = resolveTranslationPair(
            novel = null,
            plugin = TranslationConfig(enabled = true, source = "ja"),
            globalMode = true,
            globalEnabled = true,
            globalSource = "en",
            globalTarget = "ru",
        )

        assertEquals(TranslationPair("ja", "ru"), pair)
    }

    @Test
    fun `enabled novel provider wins over plugin and global`() {
        val provider = resolveTranslationProvider(
            novel = TranslationConfig(enabled = true, provider = "GEMINI"),
            plugin = TranslationConfig(enabled = true, provider = "DEEPL"),
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("GEMINI", provider)
    }

    @Test
    fun `disabled novel provider is ignored`() {
        val provider = resolveTranslationProvider(
            novel = TranslationConfig(enabled = false, provider = "GEMINI"),
            plugin = TranslationConfig(enabled = true, provider = "DEEPL"),
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("DEEPL", provider)
    }

    @Test
    fun `blank novel provider falls through to the plugin`() {
        val provider = resolveTranslationProvider(
            novel = TranslationConfig(enabled = true, provider = "   "),
            plugin = TranslationConfig(enabled = true, provider = " DEEPL "),
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("DEEPL", provider)
    }

    @Test
    fun `disabled plugin provider is ignored`() {
        val provider = resolveTranslationProvider(
            novel = null,
            plugin = TranslationConfig(enabled = false, provider = "DEEPL"),
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("GOOGLE_PA", provider)
    }

    @Test
    fun `provider falls back to global`() {
        val provider = resolveTranslationProvider(
            novel = TranslationConfig(enabled = true),
            plugin = TranslationConfig(enabled = true),
            globalProvider = "GOOGLE_PA",
        )

        assertEquals("GOOGLE_PA", provider)
    }
}
