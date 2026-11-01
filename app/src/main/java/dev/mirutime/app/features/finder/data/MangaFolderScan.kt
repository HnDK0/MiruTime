package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.library.data.isPageImageName
import dev.mirutime.app.features.library.data.naturalCompare

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
)

private fun ScannedNode.cbzFiles(): List<ScannedNode> =
    children.filter { !it.isDirectory && it.name.lowercase().endsWith(".cbz") }

private fun ScannedNode.directImages(): List<ScannedNode> =
    children.filter { !it.isDirectory && isPageImageName(it.name) }

private fun ScannedNode.chapterDirs(): List<ScannedNode> =
    children.filter { it.isDirectory && it.directImages().isNotEmpty() }

private fun ScannedNode.hasComicInfo(): Boolean =
    children.any { !it.isDirectory && it.name.equals("ComicInfo.xml", ignoreCase = true) }

// "ch1", "chapter 2", "ep3", "part-4" are chapter folders. Volume-numbered
// folders are books in a collection (`Vol1` must not collapse into one book).
private val CHAPTER_DIR = Regex("^(chapter|ch|episode|ep|part)[\\s._-]*\\d+", RegexOption.IGNORE_CASE)

/**
 * One folder classification, without recursion into collections:
 * - ImageBook: loose pages or chapter-named subfolders;
 * - CbzSet: directly contained .cbz archives;
 * - Collection: child folders that are themselves books;
 * - null: nothing readable.
 * A folder with both images and cbz is an ImageBook (pages win). A folder with
 * ComicInfo and image subfolders is a chaptered ImageBook; without either
 * marker, image subfolders are treated as separate books (Collection).
 */
internal fun classifyMangaFolder(node: ScannedNode): MangaFolderKind? {
    if (!node.isDirectory) return null
    val chapterDirs = node.chapterDirs()
    if (node.directImages().isNotEmpty()) return MangaFolderKind.ImageBook
    if (node.cbzFiles().isNotEmpty()) return MangaFolderKind.CbzSet
    if (chapterDirs.isNotEmpty()) {
        val looksChaptered = node.hasComicInfo() ||
            chapterDirs.any { CHAPTER_DIR.containsMatchIn(it.name) }
        if (looksChaptered) return MangaFolderKind.ImageBook
    }
    if (node.children.any { it.isDirectory && classifyMangaFolder(it) != null }) {
        return MangaFolderKind.Collection
    }
    return null
}

// ImageBook chapter count still uses every image subfolder once a book is
// classified, including a single "Bonus" folder beside named chapters.
private fun ScannedNode.imageDirs(): List<ScannedNode> =
    children.filter { it.isDirectory && it.directImages().isNotEmpty() }

private fun imageChapterCount(node: ScannedNode): Int {
    val chapters = node.imageDirs()
    return if (chapters.isNotEmpty()) chapters.size else 1
}

internal fun ScannedNode.toFolderScan(layout: MangaFolderKind): ScannedLocalFolder {
    val firstCbz = cbzFiles().sortedWith { a, b -> naturalCompare(a.name, b.name) }.firstOrNull()
    return ScannedLocalFolder(
        uri = uri,
        name = name,
        documentId = documentId,
        layout = layout,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        chapterCount = when (layout) {
            MangaFolderKind.CbzSet -> cbzFiles().size
            MangaFolderKind.ImageBook -> imageChapterCount(this)
            MangaFolderKind.SingleCbz -> 1
            MangaFolderKind.Collection -> null
        },
        primaryChildUri = firstCbz?.uri,
    )
}

/**
 * Turns one configured tree into manga catalogue entries. Collections are
 * flattened into their book children so every card is openable. A directory
 * with CBZ children is always one folder entry (CbzSet), even with a single
 * archive: the folder is the book, each CBZ is a chapter. NoveLA parity:
 * `AppLocalMangaSource.collectMangaBooks` groups a directory with CBZ children
 * into one book. Standalone CBZ files (direct `local-file://` URLs) still use
 * `SingleCbz` in `LocalSafBookSource.filePlan`.
 */
internal fun collectMangaCatalog(root: ScannedNode): MangaCatalogScan {
    val files = mutableListOf<ScannedLocalFile>()
    val folders = mutableListOf<ScannedLocalFolder>()

    fun visit(node: ScannedNode) {
        when (classifyMangaFolder(node)) {
            MangaFolderKind.Collection -> node.children
                .filter { it.isDirectory }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                .forEach(::visit)

            MangaFolderKind.CbzSet -> folders += node.toFolderScan(MangaFolderKind.CbzSet)
            MangaFolderKind.ImageBook -> folders += node.toFolderScan(MangaFolderKind.ImageBook)
            // classifyMangaFolder never returns these for a directory.
            MangaFolderKind.SingleCbz, null -> Unit
        }
    }

    visit(root)
    return MangaCatalogScan(files = files, folders = folders)
}
