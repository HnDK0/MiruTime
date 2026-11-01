package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.settings.data.TranslationPair
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationProviderConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Plain snapshot of every translation setting the provider configs depend on.
 * Kept free of Compose/Android state so the resolver stays JVM-testable.
 */
data class TranslationSettingsSnapshot(
    val googlePaKeys: String = "",
    val geminiKeys: String = "",
    val geminiModel: String = "",
    val openAiKeys: String = "",
    val openAiModel: String = "",
    val openAiBaseUrl: String = "",
    val openAiCompatibleKeys: String = "",
    val openAiCompatibleModel: String = "",
    val openAiCompatibleBaseUrl: String = "",
    val anthropicKeys: String = "",
    val anthropicModel: String = "",
    val deeplKeys: String = "",
    val yandexCloudKeys: String = "",
    val yandexCloudFolderId: String = "",
    val maxOutputTokens: Int = 0,
    val timeoutSeconds: Int = 180,
    val maxAttempts: Int = 6,
    val maxCharsPerRequest: Int = 0,
    val chunkConcurrency: Int = 8,
)

private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"

private fun String.trimmedOrNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun parseApiKeys(raw: String): List<String> =
    raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

/** Maps the settings snapshot to one provider config per [ProviderId]. */
fun resolveProviderConfigs(
    settings: TranslationSettingsSnapshot,
): List<TranslationProviderConfig> {
    val maxOutputTokens = settings.maxOutputTokens.takeIf { it > 0 }
    val maxCharsPerRequest = settings.maxCharsPerRequest.takeIf { it > 0 }
    val timeout = settings.timeoutSeconds.coerceAtLeast(1).seconds
    val maxAttempts = settings.maxAttempts.coerceAtLeast(1)

    fun config(
        provider: ProviderId,
        apiKeys: String = "",
        model: String? = null,
        baseUrl: String? = null,
        folderId: String? = null,
    ): TranslationProviderConfig {
        val cap = if (provider == ProviderId.YANDEX_PA) 16 else 32
        return TranslationProviderConfig(
            provider = provider,
            apiKeys = parseApiKeys(apiKeys),
            model = model?.trimmedOrNull(),
            baseUrl = baseUrl?.trimmedOrNull(),
            maxOutputTokens = maxOutputTokens,
            timeout = timeout,
            maxAttempts = maxAttempts,
            maxCharsPerRequest = maxCharsPerRequest,
            folderId = folderId?.trimmedOrNull(),
            useJsonSchema = false,
            chunkConcurrency = settings.chunkConcurrency.coerceIn(1, cap),
        )
    }

    return listOf(
        config(ProviderId.GOOGLE_PA, apiKeys = settings.googlePaKeys),
        config(ProviderId.GOOGLE_FREE),
        config(ProviderId.GEMINI, apiKeys = settings.geminiKeys, model = settings.geminiModel),
        config(
            ProviderId.OPENAI,
            apiKeys = settings.openAiKeys,
            model = settings.openAiModel,
            baseUrl = settings.openAiBaseUrl.trim().ifBlank { OPENAI_DEFAULT_BASE_URL },
        ),
        config(
            ProviderId.OPENAI_COMPATIBLE,
            apiKeys = settings.openAiCompatibleKeys,
            model = settings.openAiCompatibleModel,
            baseUrl = settings.openAiCompatibleBaseUrl,
        ),
        config(ProviderId.ANTHROPIC, apiKeys = settings.anthropicKeys, model = settings.anthropicModel),
        config(ProviderId.DEEPL, apiKeys = settings.deeplKeys),
        config(
            ProviderId.YANDEX_CLOUD,
            apiKeys = settings.yandexCloudKeys,
            folderId = settings.yandexCloudFolderId,
        ),
        config(ProviderId.YANDEX_FREE),
        config(ProviderId.YANDEX_PA),
    )
}

/** Translation is on when any of the three levels agrees. */
fun resolveTranslationEnabled(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
    globalEnabled: Boolean,
): Boolean = novel?.enabled == true || plugin?.enabled == true || globalEnabled

/**
 * FULL scope (title + description + chapter titles) cascades like the pair:
 * an enabled novel decides, then an enabled plugin. A disabled level never
 * leaks its stored flag, and the global level has no scope of its own.
 */
fun resolveTranslationFull(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
): Boolean = novel?.takeIf { it.enabled }?.full
    ?: plugin?.takeIf { it.enabled }?.full
    ?: false

/**
 * Pair cascade: enabled per-novel → enabled plugin → global. Mirrors NoveLA's
 * `resolveTranslationPair`: a disabled novel does not leak its stored pair,
 * and a disabled plugin never contributes one.
 */
fun resolveTranslationPair(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
    globalMode: Boolean,
    globalEnabled: Boolean,
    globalSource: String,
    globalTarget: String,
): TranslationPair {
    val novelPair = novel?.takeIf { it.enabled }?.toPairOrNull()
    val pluginPair = plugin?.takeIf { it.enabled }?.toPairOrNull()
    val globalPair = if (globalMode && globalEnabled) {
        TranslationPair(globalSource, globalTarget)
    } else {
        TranslationPair("", "")
    }
    val pair = novelPair ?: pluginPair ?: return globalPair
    // The editor renders a blank side as "Global — <language>", so a saved
    // side overrides its level while a blank one falls back to the next level.
    // The global languages are defaults, not part of the global-mode switch:
    // a target-only per-novel choice must still inherit the global source.
    val fallback = when {
        novelPair != null -> pluginPair ?: TranslationPair(globalSource, globalTarget)
        else -> TranslationPair(globalSource, globalTarget)
    }
    return TranslationPair(
        source = pair.source.ifBlank { fallback.source },
        target = pair.target.ifBlank { fallback.target },
    )
}

/**
 * Provider cascade: enabled per-novel → enabled plugin → global. A disabled
 * level never contributes its stored provider, and a blank one falls through
 * to the next level.
 */
fun resolveTranslationProvider(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
    globalProvider: String,
): String {
    val novelProvider = novel?.takeIf { it.enabled }?.provider?.trim()
    if (!novelProvider.isNullOrEmpty()) return novelProvider

    val pluginProvider = plugin?.takeIf { it.enabled }?.provider?.trim()
    if (!pluginProvider.isNullOrEmpty()) return pluginProvider

    return globalProvider
}

private fun TranslationConfig.toPairOrNull(): TranslationPair? {
    val rawSource = source.trim()
    val rawTarget = target.trim()
    return if (rawSource.isEmpty() && rawTarget.isEmpty()) null else TranslationPair(rawSource, rawTarget)
}

/** Plugin id encoded in a book URL: "fake://toonsource/book" -> "toonsource". */
internal fun pluginIdOf(bookUrl: String): String {
    val rest = bookUrl.substringAfter("://", bookUrl)
    return rest.substringBefore('/').ifBlank { bookUrl }
}
