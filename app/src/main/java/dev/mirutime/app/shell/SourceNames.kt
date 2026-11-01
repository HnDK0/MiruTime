package dev.mirutime.app.shell

import dev.mirutime.app.features.finder.domain.LocalSources

// One display-name rule for library cards, dashboard cards, history/updates
// rows and the shell header. Plugin ids resolve to the installed plugin name;
// the two built-in local sources get their localized names; unknown ids fall
// back to the id (backup/restored data, uninstalled plugins).
internal fun sourceDisplayName(
    id: String,
    pluginName: (String) -> String?,
    localBooks: String,
    localManga: String,
): String = when (id) {
    LocalSources.Books -> localBooks
    LocalSources.Manga -> localManga
    else -> pluginName(id) ?: id
}
