package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.data.library.bookKeyInstallId
import dev.mirutime.data.library.bookKeyUrl
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId

/** Bump when the metadata prompt/parse pipeline changes. */
const val MetadataPipelineVersion = "metadata-1"

/**
 * Explicit in-memory/persisted identity. Using the data class hashCode here
 * would be opaque and easy to regress; this string always names every part of
 * the translation identity, including source and pipeline version.
 */
internal fun MetadataConfig.cacheKey(): String = listOf(
    source?.value.orEmpty(),
    target.value,
    provider.name,
    ChapterTranslationPlanner.promptHash(prompt),
    MetadataPipelineVersion,
).joinToString("|")

/** Exact persisted identity of this config; the only key a row may match. */
internal fun MetadataConfig.storeKey(): MetadataTranslationKey = MetadataTranslationKey(
    source = source?.value.orEmpty(),
    target = target.value,
    provider = provider.name,
    promptHash = ChapterTranslationPlanner.promptHash(prompt),
    pipelineVersion = MetadataPipelineVersion,
)

/**
 * FULL-mode config of one book from the fake settings snapshot. Null means
 * metadata translation is off, has no complete pair, or the stored values do
 * not parse. The controller never touches settings itself (JVM-testable).
 */
fun metadataConfigFor(bookUrl: String): MetadataConfig? {
    val actualUrl = bookKeyUrl(bookUrl)
    val sourceId = bookKeyInstallId(bookUrl) ?: translationSourceId(actualUrl)
    val plugin = AppSettings.pluginTranslation[sourceId]
    val mode = translationMode(
        novel = AppSettings.novelTranslation[bookUrl]
        ?: AppSettings.novelTranslation[actualUrl],
        plugin = plugin,
        globalEnabled = AppSettings.translationGlobalMode,
        globalSource = AppSettings.globalSource,
        globalTarget = AppSettings.globalTarget,
        globalProvider = AppSettings.provider,
    ) ?: return null
    // Titles/description translate whenever translation is ON with a complete
    // pair, including the standard/global scope — not only a book/plugin FULL
    // override (FR-112).
    if (!mode.hasPair) return null

    val target = runCatching { LanguageCode.parse(mode.target) }.getOrNull() ?: return null
    val source = runCatching { LanguageCode.parse(mode.source) }.getOrNull()
    val provider = ProviderId.from(mode.provider) ?: return null
    val novelPrompt = AppSettings.novelPrompts.firstOrNull {
        it.bookUrl == bookUrl || it.bookUrl == actualUrl
    }
    val prompt = composePrompt(
        base = AppSettings.activePrompt,
        override = novelPrompt?.prompt.orEmpty(),
        append = novelPrompt?.append == true,
    )
    return MetadataConfig(
        source = source,
        target = target,
        provider = provider,
        prompt = prompt,
    )
}
