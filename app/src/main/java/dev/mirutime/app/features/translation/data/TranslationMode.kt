package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig

/**
 * The active translation mode of a book after the per-book config falls back
 * to the enabled plugin and then to the global one - the same resolution the
 * settings screens use. Null means translation is off for this book.
 *
 * Pair and provider share the cascade: enabled novel → enabled plugin →
 * global. A disabled or blank level never leaks into the next one.
 */
data class TranslationMode(
    val source: String,
    val target: String,
    val provider: String,
    // FULL also translates the title, description and chapter titles.
    val full: Boolean = false,
) {
    /** Both languages are known and differ, so the badge can show a pair. */
    val hasPair: Boolean get() = source.isNotBlank() && target.isNotBlank() && source != target
}

fun translationMode(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
    globalEnabled: Boolean,
    globalSource: String,
    globalTarget: String,
    globalProvider: String,
): TranslationMode? {
    if (!resolveTranslationEnabled(novel, plugin, globalEnabled)) return null
    val pair = resolveTranslationPair(
        novel = novel,
        plugin = plugin,
        globalMode = AppSettings.translationGlobalMode,
        globalEnabled = globalEnabled,
        globalSource = globalSource,
        globalTarget = globalTarget,
    )
    val provider = resolveTranslationProvider(novel, plugin, globalProvider)
    return TranslationMode(
        source = pair.source,
        target = pair.target,
        provider = provider,
        full = resolveTranslationFull(novel, plugin),
    )
}
