package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.local.data.MAX_LOCAL_SCAN_DEPTH
import dev.mirutime.app.features.local.data.isPageImageName
import dev.mirutime.app.features.local.data.naturalCompare

// Pure SAF tree snapshot shared by the scanner and unit tests. `uri` is the
// concrete document URI built with the configured tree, never a display name.
internal data class ScannedNode(
    val uri: String,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val children: List<ScannedNode> = emptyList(),
)

internal data class MangaCatalogScan(
    val files: List<ScannedLocalFile> = emptyList(),
    val folders: List<ScannedLocalFolder> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * One chapter of a book. A chapter is either an archive or a folder with
 * directly contained page images; [title] is the path from the book root
 * (`Vol 1 / Глава 16`), so nested volumes never become separate books.
 */
internal data class ScannedChapterUnit(
    val title: String,
    val archiveUri: String? = null,
    val pages: List<String> = emptyList(),
)

private const val MAX_CHAPTER_DEPTH = MAX_LOCAL_SCAN_DEPTH

// Thumbnail caches and hidden folders are never chapters. This is a safety
// filter, not a marker: chapter/book boundaries never depend on the name.
private fun isIgnoredDirectoryName(name: String): Boolean =
    name.startsWith(".") || name.lowercase().startsWith("thumb")

private fun ScannedNode.pageUris(): List<String> =
    children
        .filter { !it.isDirectory && isPageImageName(it.name) }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { it.uri }

private fun ScannedNode.sortedChildren(): List<ScannedNode> =
    children.sortedWith { a, b -> naturalCompare(a.name, b.name) }

/**
 * First ComicInfo.xml in the book: the root file wins, then a depth-first
 * search through chapter folders. Used when the book root has no metadata.
 */
internal fun firstComicInfoUri(node: ScannedNode): String? {
    node.sortedChildren()
        .firstOrNull { !it.isDirectory && it.name.equals("ComicInfo.xml", ignoreCase = true) }
        ?.let { return it.uri }
    for (child in node.sortedChildren()) {
        if (child.isDirectory) firstComicInfoUri(child)?.let { return it }
    }
    return null
}

/**
 * Chapters of ONE book: every nested folder with direct pages, and every `.cbz`
 * at any depth, in natural order. The book root's own loose pages are a cover
 * when chapters exist and the single chapter otherwise. A folder that is itself
 * a chapter is not descended into. Chapter titles are the path from the book
 * root, so `Vol 1/Глава 16` reads as `Vol 1 / Глава 16`.
 */
internal fun collectMangaChapters(
    book: ScannedNode,
    warnings: MutableList<String>? = null,
): List<ScannedChapterUnit> {
    val units = mutableListOf<ScannedChapterUnit>()

    fun walk(node: ScannedNode, prefix: List<String>, depth: Int) {
        if (depth > MAX_CHAPTER_DEPTH) {
            warnings?.add("Folder tree too deep at ${node.name}")
            return
        }
        val pages = node.pageUris()
        if (prefix.isNotEmpty() && pages.isNotEmpty()) {
            units += ScannedChapterUnit(title = prefix.joinToString(" / "), pages = pages)
            return
        }
        for (child in node.sortedChildren()) {
            when {
                child.isDirectory && !isIgnoredDirectoryName(child.name) ->
                    walk(child, prefix + child.name, depth + 1)
                child.name.lowercase().endsWith(".cbz") -> units += ScannedChapterUnit(
                    title = (prefix + child.name.substringBeforeLast('.')).joinToString(" / "),
                    archiveUri = child.uri,
                )
            }
        }
    }

    walk(book, emptyList(), depth = 0)

    if (units.isEmpty()) {
        val pages = book.pageUris()
        if (pages.isNotEmpty()) {
            units += ScannedChapterUnit(title = book.name, pages = pages)
        }
    }
    return units
}

/**
 * Catalogue entries of one configured folder: the first level is always a book.
 * A folder becomes a book card; a standalone `.cbz` becomes its own book card.
 * Everything below a book is a chapter (see [collectMangaChapters]).
 */
internal fun collectMangaCatalog(root: ScannedNode): MangaCatalogScan {
    val files = mutableListOf<ScannedLocalFile>()
    val folders = mutableListOf<ScannedLocalFolder>()
    val warnings = mutableListOf<String>()

    for (child in root.sortedChildren()) {
        if (!child.isDirectory) {
            if (child.name.lowercase().endsWith(".cbz")) {
                files += ScannedLocalFile(
                    uri = child.uri,
                    name = child.name,
                    format = "CBZ",
                    sizeBytes = child.sizeBytes,
                    lastModified = child.lastModified,
                    mangaLayout = MangaFolderKind.SingleCbz,
                    chapterCount = 1,
                )
            }
            continue
        }
        val chapters = collectMangaChapters(child, warnings)
        if (chapters.isEmpty()) continue
        val layout = when {
            chapters.all { it.archiveUri != null } -> MangaFolderKind.CbzSet
            chapters.none { it.archiveUri != null } -> MangaFolderKind.ImageBook
            else -> MangaFolderKind.Mixed
        }
        folders += ScannedLocalFolder(
            uri = child.uri,
            name = child.name,
            documentId = child.documentId,
            layout = layout,
            sizeBytes = child.sizeBytes,
            lastModified = child.lastModified,
            chapterCount = chapters.size,
            primaryChildUri = chapters.firstNotNullOfOrNull {
                it.archiveUri ?: it.pages.firstOrNull()
            },
        )
    }

    return MangaCatalogScan(files = files, folders = folders, warnings = warnings)
}
