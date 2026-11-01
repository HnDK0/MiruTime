package dev.mirutime.app.features.finder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaFolderScanTest {

    private fun file(name: String, documentId: String = name, size: Long = 10) = ScannedNode(
        uri = "content://test/document/$documentId",
        documentId = documentId,
        name = name,
        isDirectory = false,
        sizeBytes = size,
        lastModified = 100,
    )

    private fun dir(name: String, vararg children: ScannedNode) = ScannedNode(
        uri = "content://test/document/$name",
        documentId = name,
        name = name,
        isDirectory = true,
        lastModified = 200,
        children = children.toList(),
    )

    @Test
    fun `classifies image folders cbz sets collections and empty folders`() {
        val imageBook = dir("Book", file("001.jpg"), file("002.jpg"))
        val cbzSet = dir("Set", file("a.cbz"), file("b.cbz"))
        val chapterBook = dir("Chapters", dir("ch1", file("001.jpg")), dir("ch2", file("1.png")))
        val collection = dir("Library", dir("One", file("001.jpg")), dir("Two", file("001.jpg")))
        val empty = dir("Empty")

        assertEquals(MangaFolderKind.ImageBook, classifyMangaFolder(imageBook))
        assertEquals(MangaFolderKind.CbzSet, classifyMangaFolder(cbzSet))
        assertEquals(MangaFolderKind.ImageBook, classifyMangaFolder(chapterBook))
        assertEquals(MangaFolderKind.Collection, classifyMangaFolder(collection))
        assertNull(classifyMangaFolder(empty))
        assertNull(classifyMangaFolder(file("book.epub")))
    }

    @Test
    fun `a folder with one cbz is a folder book not a flattened file`() {
        // Root and child have the same rule: the directory is the book and
        // each CBZ is a chapter, even when there is exactly one.
        val root = collectMangaCatalog(dir("Single", file("book.cbz")))
        assertTrue(root.files.isEmpty())
        assertEquals(1, root.folders.size)
        assertEquals(MangaFolderKind.CbzSet, root.folders.single().layout)
        assertEquals("Single", root.folders.single().name)
        assertEquals(1, root.folders.single().chapterCount)
        assertEquals("content://test/document/book.cbz", root.folders.single().primaryChildUri)

        val child = collectMangaCatalog(
            dir("Library", dir("OneShot", file("only.cbz"))),
        )
        assertTrue(child.files.isEmpty())
        assertEquals("OneShot", child.folders.single().name)
        assertEquals(MangaFolderKind.CbzSet, child.folders.single().layout)
        assertEquals(1, child.folders.single().chapterCount)
    }

    @Test
    fun `several cbz are one naturally ordered set`() {
        val set = collectMangaCatalog(
            dir("Set", file("vol10.cbz", "10"), file("vol2.cbz", "2"), file("vol1.cbz", "1")),
        )
        assertTrue(set.files.isEmpty())
        assertEquals(3, set.folders.single().chapterCount)
        assertEquals("content://test/document/1", set.folders.single().primaryChildUri)
    }

    @Test
    fun `image folders stay image books at root and in a collection`() {
        val root = collectMangaCatalog(dir("OneShot", file("001.jpg"), file("002.jpg")))
        assertTrue(root.files.isEmpty())
        assertEquals(MangaFolderKind.ImageBook, root.folders.single().layout)
        assertEquals(1, root.folders.single().chapterCount)

        val child = collectMangaCatalog(
            dir("Library", dir("Vol2", file("001.jpg")), dir("Vol1", file("001.jpg"))),
        )
        assertEquals(listOf("Vol1", "Vol2"), child.folders.map { it.name })
        assertTrue(child.folders.all { it.layout == MangaFolderKind.ImageBook })
    }

    @Test
    fun `image book chapters count subdirectories and fall back to one chapter`() {
        val chaptered = collectMangaCatalog(
            dir("Book", dir("ch2", file("001.jpg")), dir("ch1", file("001.jpg")), file("cover.jpg")),
        )
        assertEquals(2, chaptered.folders.single().chapterCount)

        val single = collectMangaCatalog(dir("OneShot", file("001.jpg"), file("002.jpg")))
        assertEquals(1, single.folders.single().chapterCount)
    }

    @Test
    fun `empty tree yields nothing`() {
        val catalog = collectMangaCatalog(dir("Empty", dir("Nothing")))
        assertTrue(catalog.files.isEmpty())
        assertTrue(catalog.folders.isEmpty())
    }

    @Test
    fun `scan warnings survive an otherwise empty folder`() {
        val entry = ScannedLocalFile("content://doc/one.cbz", "one.cbz", "CBZ")
        val scan = buildLocalCatalogScan(
            kind = "manga",
            folders = listOf(
                LocalFolderScan(
                    uri = "content://tree/manga",
                    name = "Manga",
                    files = listOf(entry),
                    warnings = listOf("Cannot read Manga: SecurityException"),
                ),
            ),
        )

        assertEquals(listOf(entry.uri), scan.entries.map { it.uri })
        assertEquals(listOf("Cannot read Manga: SecurityException"), scan.warnings)
    }
}
