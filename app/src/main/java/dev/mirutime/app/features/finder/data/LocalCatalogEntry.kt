package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogPage
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.applyCatalogFilters
import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LocalBookFileEntity
import java.net.URI
import java.util.Locale

// Manga folder entries can be opened without importing one file at a time.
// Collection is a scan classification (its children are flattened into cards),
// the other three are real catalogue entries.
enum class MangaFolderKind { SingleCbz, CbzSet, ImageBook, Collection }

enum class LocalCatalogEntryType { File, Folder }

// One file or folder discovered in a configured SAF folder. Identity is the
// concrete SAF document URI, never the title or file name: a rename/re-import
// must not change the catalogue key.
data class LocalCatalogEntry(
    val uri: String,
    val displayName: String,
    val format: String,
    val kind: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val folderUri: String,
    val folderName: String,
    val entryType: LocalCatalogEntryType = LocalCatalogEntryType.File,
    val mangaLayout: MangaFolderKind? = null,
    // Set on folder entries so a transient tree import can start at the
    // document the scanner found instead of re-walking the whole configured
    // tree. Derived from the document URI in the provider otherwise.
    val documentId: String? = null,
    // CbzSet covers/titles come from the first archive.
    val primaryChildUri: String? = null,
    // Metadata resolved without importing (cache, see LocalCatalogMetadata).
    val embeddedTitle: String? = null,
    val embeddedAuthor: String? = null,
    val embeddedDescription: String? = null,
    val coverUrl: String? = null,
    val chapterCount: Int? = null,
    // Registry-assigned short id (may be longer than the base prefix after a
    // collision). Null falls back to the pure SHA prefix for tests/previews.
    val catalogId: String? = null,
) {
    val isManga: Boolean get() = kind == "manga"
    val isFolder: Boolean get() = entryType == LocalCatalogEntryType.Folder
    val title: String
        get() = embeddedTitle?.takeIf { it.isNotBlank() }
            ?: if (isFolder) displayName else displayName.substringBeforeLast('.').ifBlank { displayName }
}

// A pure snapshot of one folder's scan result: LocalSourceFolders turns the
// real SAF cursors into these, tests use literals (no Android Uri needed).
internal data class ScannedLocalFile(
    val uri: String,
    val name: String,
    val format: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val mangaLayout: MangaFolderKind? = null,
    val chapterCount: Int? = null,
)

internal data class ScannedLocalFolder(
    val uri: String,
    val name: String,
    val documentId: String,
    val layout: MangaFolderKind,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val chapterCount: Int? = null,
    val primaryChildUri: String? = null,
)

internal data class LocalFolderScan(
    val uri: String,
    val name: String,
    val files: List<ScannedLocalFile>,
    val folders: List<ScannedLocalFolder> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class LocalCatalogScan(
    val entries: List<LocalCatalogEntry>,
    val warnings: List<String> = emptyList(),
)

// Flattens every configured folder into one catalogue. Duplicate document
// URIs (nested folders, the same tree added twice) collapse to the first hit;
// the result keeps folder/name order so a rescan is deterministic.
internal fun buildLocalCatalog(
    kind: String,
    folders: List<LocalFolderScan>,
): List<LocalCatalogEntry> = buildLocalCatalogScan(kind, folders).entries

internal fun buildLocalCatalogScan(
    kind: String,
    folders: List<LocalFolderScan>,
): LocalCatalogScan {
    val seen = HashSet<String>()
    val result = ArrayList<LocalCatalogEntry>()
    val warnings = ArrayList<String>()
    for (folder in folders) {
        warnings += folder.warnings
        for (file in folder.files) {
            if (!seen.add(file.uri)) continue
            result += LocalCatalogEntry(
                uri = file.uri,
                displayName = file.name,
                format = file.format,
                kind = kind,
                sizeBytes = file.sizeBytes,
                lastModified = file.lastModified,
                folderUri = folder.uri,
                folderName = folder.name,
                mangaLayout = file.mangaLayout,
                chapterCount = file.chapterCount,
            )
        }
        for (dir in folder.folders) {
            if (!seen.add(dir.uri)) continue
            result += LocalCatalogEntry(
                uri = dir.uri,
                displayName = dir.name,
                format = formatForLayout(dir.layout),
                kind = kind,
                sizeBytes = dir.sizeBytes,
                lastModified = dir.lastModified,
                folderUri = folder.uri,
                folderName = folder.name,
                entryType = LocalCatalogEntryType.Folder,
                mangaLayout = dir.layout,
                documentId = dir.documentId,
                primaryChildUri = dir.primaryChildUri,
                chapterCount = dir.chapterCount,
            )
        }
    }
    return LocalCatalogScan(result, warnings)
}

private fun formatForLayout(layout: MangaFolderKind): String = when (layout) {
    MangaFolderKind.ImageBook -> "IMAGES"
    else -> "CBZ"
}

// Imported rows are joined by the same document URI the scanner emits, so an
// entry already in the library keeps its opaque local:// URL, real title,
// chapter count and cover.
data class ImportedLocalBook(
    val bookUrl: String,
    val title: String,
    val chapters: Int,
    val coverUrl: String? = null,
)

// Import-on-open dedupe: only the exact scanned document URI resolves to an
// existing book. A mismatched URI must fall through to the real importer.
internal fun existingLocalCatalogImport(
    documentUri: String,
    file: LocalBookFileEntity?,
    book: BookEntity?,
): LocalImportResult.Success? {
    if (file == null || book == null) return null
    if (file.uri != documentUri || file.bookId != book.bookId) return null
    return LocalImportResult.Success(
        bookId = book.bookId,
        bookUrl = book.url,
        title = book.title,
        duplicate = true,
    )
}

internal fun LocalCatalogEntry.toCatalogBook(imported: ImportedLocalBook? = null): CatalogBook =
    if (imported != null) {
        CatalogBook(
            bookUrl = imported.bookUrl,
            installId = LocalSources.idOfKind(kind),
            title = imported.title.ifBlank { title },
            rating = 0f,
            chapters = imported.chapters,
            genres = emptyList(),
            ongoing = false,
            coverUrl = imported.coverUrl ?: coverUrl,
            localFormat = format,
            notImported = false,
        )
    } else {
        CatalogBook(
            bookUrl = localCatalogUrl(uri, catalogId),
            installId = LocalSources.idOfKind(kind),
            title = title,
            rating = 0f,
            chapters = chapterCount ?: 0,
            genres = emptyList(),
            ongoing = false,
            coverUrl = coverUrl,
            localFormat = format,
            notImported = true,
        )
    }

private fun LocalCatalogEntry.matches(query: String): Boolean =
    query.isEmpty() ||
        title.contains(query, ignoreCase = true) ||
        displayName.contains(query, ignoreCase = true)

// Local files have no plugin genres/status. The format and source kind are the
// only meaningful local filter values; anything else cannot match rather than
// silently ignoring the filter. The UI must not offer plugin genres for a
// local source.
private fun LocalCatalogEntry.matchesLocalGenre(value: String): Boolean {
    val filter = value.trim().lowercase(Locale.ROOT)
    if (filter.isEmpty()) return true
    return filter == format.lowercase(Locale.ROOT) ||
        filter == kind.lowercase(Locale.ROOT) ||
        filter == if (isManga) "manga" else "books"
}

internal fun localCatalogPageForEntries(
    entries: List<LocalCatalogEntry>,
    imported: Map<String, ImportedLocalBook>,
    filters: CatalogFilters,
    page: Int,
    pageSize: Int = 18,
): CatalogPage {
    val query = filters.query.trim()
    val matched = entries.filter { entry ->
        entry.matches(query) &&
            filters.genres.all { entry.matchesLocalGenre(it) } &&
            // Static files are never "ongoing"; this is an unsupported filter.
            !filters.ongoingOnly
    }
    // The scan carries a timestamp the CatalogueBook does not, so Latest is
    // resolved here (newest first) and the shared filter then only applies the
    // requested direction. Every other sort is handled by the shared pipeline.
    val orderedEntries = if (filters.sort == CatalogSort.Latest) {
        matched.sortedByDescending { it.lastModified ?: 0L }
    } else {
        matched
    }
    val ordered = applyCatalogFilters(
        books = orderedEntries.map { it.toCatalogBook(imported[it.uri]) },
        filters = filters.copy(query = ""),
        sourceOrderIsLatest = true,
        queryAlreadyApplied = true,
    )
    val from = ((page - 1).coerceAtLeast(0)) * pageSize
    return CatalogPage(
        books = ordered.drop(from).take(pageSize),
        hasMore = from + pageSize < ordered.size,
    )
}

internal fun localCatalogSearch(
    entries: List<LocalCatalogEntry>,
    imported: Map<String, ImportedLocalBook>,
    query: String,
    limit: Int = 12,
): List<CatalogBook> {
    val needle = query.trim()
    return entries.asSequence()
        .filter { it.matches(needle) }
        .take(limit)
        .map { it.toCatalogBook(imported[it.uri]) }
        .toList()
}

// Stable catalogue URL for a file that is not in the library yet. The URL is
// `local-file://<hex id>`; the id is a SHA-256 prefix registered by
// LocalSafRegistry (extended on a hash collision), never Base64/percent text.
// This keeps URLs short and navigation-safe (no `%`, `/`, `+` or `=` payload).
const val LOCAL_CATALOG_SCHEME: String = "local-file"
private const val LOCAL_CATALOG_PREFIX = "$LOCAL_CATALOG_SCHEME://"

internal fun localCatalogUrl(
    documentUri: String,
    id: String? = null,
): String = LOCAL_CATALOG_PREFIX + (id ?: localFileId(documentUri))

// Direct-read book URL: `local-file://<id>#chapter=N`. The fragment is ours.
internal fun localFileChapterUrl(bookUrl: String, index: Int): String =
    "${bookUrl.substringBefore('#')}#chapter=$index"

internal fun localFileChapterIndex(url: String): Int? =
    url.substringAfter('#', missingDelimiterValue = "")
        .removePrefix("chapter=")
        .takeIf { url.contains("#chapter=") }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

internal fun isLocalFileBook(url: String): Boolean =
    url.startsWith(LOCAL_CATALOG_PREFIX, ignoreCase = true)

internal fun localFileIdFromUrl(url: String): String? {
    if (!url.startsWith(LOCAL_CATALOG_PREFIX, ignoreCase = true)) return null
    val id = url.substringBefore('#').substring(LOCAL_CATALOG_PREFIX.length).lowercase()
    return id.takeIf(::isLocalFileId)
}

// Unknown/invalid IDs are typed invalid; there is intentionally no legacy
// Base64/percent fallback, so pre-fix catalogue/history entries must be
// discarded and re-scanned.
internal fun localFileDocumentUri(url: String, registry: LocalSafRegistry): String? {
    val id = localFileIdFromUrl(url) ?: return null
    val documentUri = registry.uriFor(id) ?: return null
    return sanitizeDocumentUri(documentUri)
}

private fun sanitizeDocumentUri(raw: String): String? {
    if (raw.isEmpty() || raw.any { it.isISOControl() }) return null
    // Document ids are themselves percent-encoded; catch traversal both in the
    // decoded string and in a still-encoded document id.
    val lower = raw.lowercase(Locale.ROOT)
    if (lower.contains("%2e%2e") ||
        lower.contains("../") ||
        lower.contains("/..") ||
        lower.contains("..%2f") ||
        lower.contains("%2f..")
    ) {
        return null
    }
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("content", ignoreCase = true)) return null
    if (parsed.authority.isNullOrBlank()) return null
    if (parsed.fragment != null) return null
    if (parsed.path.orEmpty().split('/').any { it == "." || it == ".." }) return null
    return raw
}
