package dev.mirutime.app.shell

import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.OpdsSources

// One display-name rule for library cards, dashboard cards, history/updates
// rows and the shell header. Plugin ids resolve to the installed plugin name;
// the two built-in local sources get their localized names; an OPDS source id
// resolves to its saved catalogue name; unknown ids fall back to the id
// (backup/restored data, uninstalled plugins).
internal fun sourceDisplayName(
    id: String,
    pluginName: (String) -> String?,
    localBooks: String,
    localManga: String,
    opdsCatalogName: (catalogId: String) -> String? = { null },
): String = when {
    id == LocalSources.Books -> localBooks
    id == LocalSources.Manga -> localManga
    OpdsSources.catalogIdOf(id) != null ->
        OpdsSources.catalogIdOf(id)?.let(opdsCatalogName) ?: id
    else -> pluginName(id) ?: id
}
