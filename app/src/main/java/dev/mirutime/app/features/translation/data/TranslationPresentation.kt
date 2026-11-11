package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.data.library.bookKeyInstallId
import dev.mirutime.data.library.bookKeyUrl

/** Surfaces that can show a translated book title. */
enum class TitleSurface { Library, History, Catalog, Search }

/**
 * Effective title/chapter-title presentation for one book. The flags cascade
 * like the pair and scope: an enabled novel decides, then an enabled plugin;
 * a disabled level never leaks its stored flags. Metadata translation itself
 * still requires FULL scope; these flags only choose which titles are shown.
 */
data class TranslationPresentation(
    val chapterTitles: Boolean = true,
    val hiddenSurfaces: Set<TitleSurface> = emptySet(),
) {
    fun showsTitle(surface: TitleSurface): Boolean = surface !in hiddenSurfaces
}

/** Per-book novel/plugin configs, resolved the same way every settings screen does. */
internal fun translationConfigsFor(bookUrl: String): Pair<TranslationConfig?, TranslationConfig?> {
    val actualUrl = bookKeyUrl(bookUrl)
    val sourceId = bookKeyInstallId(bookUrl) ?: translationSourceId(actualUrl)
    return (AppSettings.novelTranslation[bookUrl] ?: AppSettings.novelTranslation[actualUrl]) to
        AppSettings.pluginTranslation[sourceId]
}

fun resolveTranslationPresentation(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
): TranslationPresentation {
    val config = novel?.takeIf { it.enabled } ?: plugin?.takeIf { it.enabled }
        ?: return TranslationPresentation()
    val hidden = buildSet {
        if (config.hideLibrary) add(TitleSurface.Library)
        if (config.hideHistory) add(TitleSurface.History)
        if (config.hideCatalog) add(TitleSurface.Catalog)
        if (config.hideSearch) add(TitleSurface.Search)
    }
    return TranslationPresentation(chapterTitles = config.chapterTitles, hiddenSurfaces = hidden)
}

fun translationPresentationFor(bookUrl: String): TranslationPresentation {
    val (novel, plugin) = translationConfigsFor(bookUrl)
    return resolveTranslationPresentation(novel, plugin)
}
