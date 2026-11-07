package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.libraryBookKey
import kotlinx.serialization.Serializable

// One installed plugin as the Browse list shows it.
// `pinned` is the user's pin, not a plugin property.
data class CatalogSource(
    val id: String,
    val name: String,
    // Short uppercase code shown on the language chip ("EN", "RU").
    val language: String,
    val type: ContentType,
    val version: String,
    val pinned: Boolean,
    // Built-in device sources (local EPUB/FB2 and CBZ): no plugin, no
    // language, never filtered out by the language filter.
    val local: Boolean = false,
    // How many of its titles are in the library (the row's middle line).
    val libraryCount: Int = 0,
    // Declared plugin icon, already resolved against the plugin's baseUrl.
    // Null = no icon: the avatar falls back to the monogram.
    val iconUrl: String? = null,
)

// The two built-in local sources. Ids are stable: routes and pins use them.
object LocalSources {
    const val Books = "local-books"
    const val Manga = "local-manga"
    fun isLocal(id: String) = id == Books || id == Manga
    fun kindOf(id: String) = if (id == Manga) "manga" else "books"
    fun idOfKind(kind: String) = if (kind == "manga") Manga else Books
}

// One configured SAF folder of a built-in local source. `fileCount` is the
// number of supported files found by the live scan, not the imported count.
data class LocalCatalogFolder(
    val uri: String,
    val name: String,
    val fileCount: Int,
    val kind: String,
)

// One imported device file (EPUB / FB2 novels, CBZ manga). `count` = chapters
// for novels, pages for manga. [bookUrl] is the opaque `local://<uuid>` DB URL,
// never derived from the file name or the SAF uri.
data class LocalBook(
    val bookUrl: String,
    val fileName: String,
    val title: String,
    val format: String,
    val sizeLabel: String,
    val count: Int,
    val bookId: Long = 0,
    // Raw installId ("local-books" / "local-manga") for install-aware keys.
    val installId: String = "",
) {
    val isManga: Boolean get() = format == "CBZ"
}

// Browse-list sorting (the Sort button of Finder).
enum class SourceSort { Name, Library, Language }

// Four independent Finder filter buttons: type, language, sort (pins are a
// per-source toggle, not a filter). Empty set = no restriction.
data class BrowseFilters(
    val types: Set<ContentType> = emptySet(),
    val languages: Set<String> = emptySet(),
    val sort: SourceSort = SourceSort.Name,
    val descending: Boolean = false,
) {
    val sortChanged: Boolean
        get() = sort != SourceSort.Name || descending
}

// Optimistic pin state for the Pins sheet. The screen flips one row first and
// calls the same helper with the old value to revert a failed write.
internal fun List<CatalogSource>.withPin(sourceId: String, pinned: Boolean): List<CatalogSource> =
    map { if (it.id == sourceId) it.copy(pinned = pinned) else it }

// Pinned first; pinned LOCAL sources above every other pinned one; the rest
// by the chosen sort. Local sources ignore the language filter.
fun List<CatalogSource>.applyBrowse(filters: BrowseFilters): List<CatalogSource> {
    val filtered = filter { filters.types.isEmpty() || it.type in filters.types }
        .filter { it.local || filters.languages.isEmpty() || it.language in filters.languages }
    val byKey: Comparator<CatalogSource> = when (filters.sort) {
        SourceSort.Name -> compareBy { it.name.lowercase() }
        SourceSort.Library -> compareBy<CatalogSource> { it.libraryCount }.thenBy { it.name.lowercase() }
        SourceSort.Language -> compareBy<CatalogSource> { it.language }.thenBy { it.name.lowercase() }
    }
    val keyed = if (filters.descending) byKey.reversed() else byKey
    return filtered.sortedWith(
        compareBy<CatalogSource> { !(it.pinned && it.local) }
            .thenBy { !it.pinned }
            .then(keyed),
    )
}

enum class ExtensionStatus { Any, Enabled, Disabled, Updates }

// Manage-extensions filters (one bottom sheet): query + type + language +
// (installed tab only) status.
data class ExtensionFilters(
    val query: String = "",
    val types: Set<ContentType> = emptySet(),
    val languages: Set<String> = emptySet(),
    val status: ExtensionStatus = ExtensionStatus.Any,
) {
    val activeFilterCount: Int
        get() = types.size + languages.size + (if (status != ExtensionStatus.Any) 1 else 0)
}

// A catalogue entry: lighter than BookDetails, the full page is built when
// the user opens it (same bookUrl key as everywhere else).
data class CatalogBook(
    val bookUrl: String,
    val title: String,
    // 0 = the plugin does not expose a rating -> no star on the tile.
    val rating: Float,
    val chapters: Int,
    val genres: List<String>,
    val ongoing: Boolean,
    val coverUrl: String? = null,
    // Local catalog only: the scanned file's format, and whether the entry is
    // still just a file on disk (local-file://) rather than an imported book.
    val localFormat: String? = null,
    val notImported: Boolean = false,
    // Filled by the provider from one batch library lookup, never guessed.
    val inLibrary: Boolean = false,
    // NoveLA bookmark: URL match = same source, title-only = another plugin.
    val badge: LibraryBadgeState? = null,
    // Raw plugin id. Two plugins may expose different works under one URL.
    val installId: String = "",
) {
    val bookKey: String = libraryBookKey(installId, bookUrl)
}

data class LibraryBadgeState(val inSameSource: Boolean, val sourceCount: Int)

internal fun libraryBadgeState(
    bookKey: String,
    title: String,
    libraryUrls: Set<String>,
    titleCounts: Map<String, Int>,
    bookUrl: String = bookKey,
    installId: String = "",
    libraryKeys: Set<String> = emptySet(),
): LibraryBadgeState? {
    val count = titleCounts[title] ?: 0
    val sameSource = bookKey in libraryKeys || (installId.isBlank() && bookUrl in libraryUrls)
    if (sameSource) return LibraryBadgeState(inSameSource = true, sourceCount = maxOf(1, count))
    if (count > 0) return LibraryBadgeState(inSameSource = false, sourceCount = count)
    return null
}

// One batch result applied to already-loaded cards: no reload of the page and
// no per-card DB query. Used by providers after mapping and by the screens
// after a long-press toggle.
internal fun List<CatalogBook>.setMembership(
    bookKey: String,
    inLibrary: Boolean,
): List<CatalogBook> = map {
    if (it.bookKey == bookKey) {
        val old = it.badge
        val badge = if (inLibrary) {
            LibraryBadgeState(
                inSameSource = true,
                sourceCount = maxOf(1, (old?.sourceCount ?: 0) + 1),
            )
        } else {
            val remaining = ((old?.sourceCount ?: 1) - 1).coerceAtLeast(0)
            if (remaining > 0) LibraryBadgeState(inSameSource = false, sourceCount = remaining) else null
        }
        it.copy(inLibrary = inLibrary, badge = badge)
    } else {
        it
    }
}

internal fun List<CatalogBook>.applyMembership(
    libraryUrls: Set<String>,
    titleCounts: Map<String, Int>,
    libraryKeys: Set<String> = emptySet(),
): List<CatalogBook> = map {
    val badge = libraryBadgeState(
        bookKey = it.bookKey,
        title = it.title,
        libraryUrls = libraryUrls,
        titleCounts = titleCounts,
        bookUrl = it.bookUrl,
        installId = it.installId,
        libraryKeys = libraryKeys,
    )
    // inLibrary means THIS work (installId + url) is in the library; a
    // title-only match is a muted bookmark, never a removable membership.
    val inSameSource = it.bookKey in libraryKeys ||
        (it.installId.isBlank() && it.bookUrl in libraryUrls)
    it.copy(inLibrary = inSameSource, badge = badge)
}

// Explicit URL-only helper for fakes/previews: the set carries plain URLs, so
// it matches the URL regardless of installId.
internal fun List<CatalogBook>.applyLibraryUrls(libraryUrls: Set<String>): List<CatalogBook> =
    if (libraryUrls.isEmpty()) {
        this
    } else {
        map { book ->
            val badge = libraryBadgeState(
                bookKey = book.bookUrl,
                title = book.title,
                libraryUrls = libraryUrls,
                titleCounts = emptyMap(),
            )
            book.copy(inLibrary = book.bookUrl in libraryUrls, badge = badge)
        }
    }

// Catalogue identity is installId + URL; providers can repeat an entry across
// pages or merged extensions, but two plugins may expose different works
// under the same URL.
internal fun dedupeCatalogBooks(books: List<CatalogBook>): List<CatalogBook> =
    books.distinctBy { it.bookKey }

// Sort options as a plugin would declare them (order = plugin order).
@Serializable
enum class CatalogSort { Popular, Latest, Rating, Title }

// The ONE request the search+filter sheet produces (query and filters are
// composed into one request). Draft lives in the sheet, this is applied.
@Serializable
data class CatalogFilters(
    val query: String = "",
    val sort: CatalogSort = CatalogSort.Popular,
    val descending: Boolean = true,
    val genres: Set<String> = emptySet(),
    val ongoingOnly: Boolean = false,
    // Plugin filters in their FINAL Lua keys ("genre_included",
    // "order_ascending", ...). Only values that differ from the schema defaults
    // are stored here; [defaultPluginValues] supplies the rest at request time.
    val pluginValues: Map<String, List<String>> = emptyMap(),
) {
    // Badge on the search bar: everything that is not the plain default
    // listing, the query itself is shown in the field instead. Legacy
    // genre/status fields remain for local sources and old presets.
    val activeFilterCount: Int
        get() = (if (sort != CatalogSort.Popular || !descending) 1 else 0) +
            genres.size +
            (if (ongoingOnly) 1 else 0) +
            pluginValues.count { it.value.isNotEmpty() }
}

data class CatalogPage(val books: List<CatalogBook>, val hasMore: Boolean)

// Client-side catalogue filtering shared by plugin pages and the local folder
// scan. Provider pages are one page at a time, so sorting/filtering here is
// page-local, never a global re-rank.
//
// `queryAlreadyApplied` callers already matched the query themselves (plugin
// `search`, local title/file-name match). Filtering by title again would drop
// legitimate author/file-name matches.
// `sourceOrderIsLatest` says the incoming order is newest-first, so Latest can
// use it instead of inventing dates a catalogue page does not carry. When it is
// false the provider order is preserved because recency is unknowable.
internal fun applyCatalogFilters(
    books: List<CatalogBook>,
    filters: CatalogFilters,
    sourceOrderIsLatest: Boolean = true,
    queryAlreadyApplied: Boolean = false,
): List<CatalogBook> {
    var result = books
    if (!queryAlreadyApplied) {
        val query = filters.query.trim()
        if (query.isNotEmpty()) {
            result = result.filter { it.title.contains(query, ignoreCase = true) }
        }
    }
    // Genre metadata is optional on a catalogue page. If no book reports any
    // genres, the provider's own filtering is the only source of truth: drop
    // nothing rather than turn a working plugin filter into an empty page.
    // When metadata exists, books without genres stay (unknown != no match)
    // and a book matches when any of its genres matches any selected chip.
    if (filters.genres.isNotEmpty() && result.any { it.genres.isNotEmpty() }) {
        result = result.filter { book ->
            book.genres.isEmpty() ||
                book.genres.any { genre ->
                    filters.genres.any { it.trim().equals(genre.trim(), ignoreCase = true) }
                }
        }
    }
    if (filters.ongoingOnly) result = result.filter { it.ongoing }
    return sortCatalogBooks(result, filters.sort, filters.descending, sourceOrderIsLatest)
}

// Popular/Latest preserve provider order: descending keeps it (best/newest
// first), ascending reverses it. Rating and Title have a total order with
// title (case-insensitive) and URL tie-breakers so paging/recomposition is
// deterministic.
private fun sortCatalogBooks(
    books: List<CatalogBook>,
    sort: CatalogSort,
    descending: Boolean,
    sourceOrderIsLatest: Boolean,
): List<CatalogBook> = when (sort) {
    CatalogSort.Popular -> if (descending) books else books.asReversed()
    CatalogSort.Latest -> when {
        !sourceOrderIsLatest -> books
        descending -> books
        else -> books.asReversed()
    }
    CatalogSort.Rating -> if (descending) {
        books.sortedWith(
            compareByDescending<CatalogBook> { it.rating }
                .thenBy { it.title.lowercase() }
                .thenBy { it.bookUrl },
        )
    } else {
        books.sortedWith(
            compareBy<CatalogBook> { it.rating }
                .thenBy { it.title.lowercase() }
                .thenBy { it.bookUrl },
        )
    }
    CatalogSort.Title -> if (descending) {
        books.sortedWith(
            compareByDescending<CatalogBook> { it.title.lowercase() }
                .thenBy { it.bookUrl },
        )
    } else {
        books.sortedWith(
            compareBy<CatalogBook> { it.title.lowercase() }
                .thenBy { it.bookUrl },
        )
    }
}

// Search history rules: max 5, FIFO (the oldest falls out),
// case-insensitive dedupe (a repeat moves to the top), manual delete per item
// + clear all, no time-based expiry.
object SearchHistoryRules {
    const val Max = 5
    const val GlobalKey = "__global__"

    fun push(history: List<String>, query: String): List<String> {
        val clean = query.trim()
        if (clean.isEmpty()) return history
        return (listOf(clean) + history.filterNot { it.equals(clean, ignoreCase = true) })
            .take(Max)
    }

    fun remove(history: List<String>, query: String): List<String> =
        history.filterNot { it.equals(query, ignoreCase = true) }
}

// A plugin as the Extensions tab shows it: installed or only in the repo.
// `installedVersion == null` means not installed. Disabled plugins stay
// installed but vanish from Browse (enable/disable per id).
data class PluginExtension(
    val id: String,
    val name: String,
    val language: String,
    val type: ContentType,
    val availableVersion: String,
    val installedVersion: String?,
    val enabled: Boolean,
    val iconUrl: String? = null,
    // Non-null when the plugin failed to load. Healthy plugins keep this null
    // and their row/lifecycle unchanged.
    val error: PluginError? = null,
    // The user's switch, independent of the load error: the health sheet can
    // still offer Disable for a plugin that failed to load.
    val enabledByUser: Boolean = enabled,
    // True when the repository still publishes this id: Reset script is only
    // offered for those (there is a copy to restore).
    val repoAvailable: Boolean = false,
) {
    val installed: Boolean get() = installedVersion != null
    val updateAvailable: Boolean
        get() = installedVersion != null && isNewerVersion(availableVersion, installedVersion)
    val broken: Boolean get() = installed && error != null
}

// A local .lua picked through SAF. Typed rejections let the screen show a
// precise, localized reason instead of a generic failure toast.
sealed interface PluginFileInstall {
    data class Installed(val id: String, val name: String) : PluginFileInstall
    data class Rejected(val reason: PluginFileRejection, val detail: String = "") : PluginFileInstall
}

enum class PluginFileRejection {
    NOT_LUA,
    UNREADABLE,
    EMPTY,
    TOO_LARGE,
    INVALID_UTF8,
    INVALID_ID,
    SYNTAX,
    DUPLICATE,
    WRITE,
}

data class PluginResetResult(
    val success: Boolean,
    val detail: String = "",
)

// Only http(s) links are opened from the health sheet (no file:/intent:).
fun isWebUrl(raw: String): Boolean {
    val url = raw.trim()
    return url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("http://", ignoreCase = true)
}

// One settings widget a plugin itself declares
// (`getSettingsSchema` / `lua_preferences`). Labels arrive as plain strings —
// they are the plugin's own Lua text, not app string resources. The sheet
// renders it by type, so the UI never branches on a plugin id. No Compose
// types here on purpose.
sealed interface PluginSetting {
    val key: String

    // `select` widget: <= 3 options are chips in a row, more — a value row
    // opening a one-of-N choice sheet.
    data class Select(
        override val key: String,
        val label: String,
        val options: List<PluginSettingOption>,
        val current: () -> String,
        val onChange: (String) -> Unit,
    ) : PluginSetting
}

// One Select option: `key` is the value written back to the plugin, `label`
// is shown exactly as the plugin declared it.
data class PluginSettingOption(val key: String, val label: String)

// Named filter preset: max 10 per source. The query is never part of a
// preset — it is a one-off, a preset is a reusable filter set.
@Serializable
data class FilterPreset(val name: String, val filters: CatalogFilters) {
    companion object {
        const val Max = 10
    }
}

// Result of resolving a pasted book address against installed plugins.
sealed interface LinkResolution {
    data class Found(val source: CatalogSource, val book: CatalogBook) : LinkResolution

    // Host belongs to no installed plugin; `suggestion` is a repo plugin that
    // would handle it (offered as one-tap install), null when none matches.
    data class UnknownHost(val host: String, val suggestion: PluginExtension?) : LinkResolution

    // The URL host matched an installed source, but that source failed to load
    // it (CF/HTTP/parse). Distinct from Invalid: the pasted address IS a web
    // link; the source is the problem and the message must say so.
    data class SourceError(
        val host: String,
        val sourceName: String,
        val reason: String,
    ) : LinkResolution

    data object Invalid : LinkResolution
}

fun List<PluginExtension>.applyFilters(
    filters: ExtensionFilters,
    installedTab: Boolean,
): List<PluginExtension> = this
    .filter { filters.query.isBlank() || it.name.contains(filters.query.trim(), ignoreCase = true) }
    .filter { filters.types.isEmpty() || it.type in filters.types }
    .filter { filters.languages.isEmpty() || it.language in filters.languages }
    .filter {
        !installedTab || when (filters.status) {
            ExtensionStatus.Any -> true
            ExtensionStatus.Enabled -> it.enabled
            ExtensionStatus.Disabled -> !it.enabled
            ExtensionStatus.Updates -> it.updateAvailable
        }
    }
    .sortedBy { it.name.lowercase() }
