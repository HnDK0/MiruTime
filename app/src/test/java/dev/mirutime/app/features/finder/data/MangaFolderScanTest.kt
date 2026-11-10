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

    private fun titles(book: ScannedNode) = collectMangaChapters(book).map { it.title }

    @Test
    fun `first level is always a book, standalone cbz included`() {
        val catalog = collectMangaCatalog(
            dir(
                "Library",
                file("X.cbz", "x"),
                dir("Title", dir("Глава 16", file("001.jpg", "p1"))),
            ),
        )

        assertEquals(1, catalog.files.size)
        assertEquals("X.cbz", catalog.files.single().name)
        assertEquals(MangaFolderKind.SingleCbz, catalog.files.single().mangaLayout)
        assertEquals(1, catalog.files.single().chapterCount)

        assertEquals(1, catalog.folders.size)
        assertEquals("Title", catalog.folders.single().name)
        assertEquals(MangaFolderKind.ImageBook, catalog.folders.single().layout)
        assertEquals(1, catalog.folders.single().chapterCount)
    }

    @Test
    fun `a tree deeper than twelve is truncated with a warning`() {
        var node = dir("chapter", file("001.jpg"))
        repeat(13) { index -> node = dir("d$index", node) }
        val warnings = mutableListOf<String>()

        val chapters = collectMangaChapters(dir("Book", node), warnings)

        assertTrue(chapters.isEmpty())
        assertTrue(warnings.any { it.contains("too deep") })
    }

    @Test
    fun `a tree within the limit is not truncated`() {
        var node = dir("chapter", file("001.jpg"))
        repeat(9) { index -> node = dir("d$index", node) }
        val warnings = mutableListOf<String>()

        val chapters = collectMangaChapters(dir("Book", node), warnings)

        assertTrue(chapters.isNotEmpty())
        assertTrue(warnings.none { it.contains("too deep") })
    }

    @Test
    fun `chapter title is the path from the book root`() {
        val book = dir("Title", dir("Vol 1", dir("Глава 16", file("001.jpg"))))

        assertEquals(listOf("Vol 1 / Глава 16"), titles(book))
    }

    @Test
    fun `every nested folder with pages is a chapter in natural order`() {
        val book = dir(
            "Title",
            dir("ch2", file("001.jpg")),
            dir("ch1", file("001.jpg")),
            dir("Extra", file("bonus.jpg")),
        )

        assertEquals(listOf("Extra", "ch1", "ch2"), titles(book))
    }

    @Test
    fun `leaf folder with pages is a chapter even when named like a volume`() {
        val book = dir("Title", dir("Vol 1", file("001.jpg"), file("002.jpg")))

        val chapter = collectMangaChapters(book).single()
        assertEquals("Vol 1", chapter.title)
        assertEquals(2, chapter.pages.size)
    }

    @Test
    fun `folder with archives and image chapters is mixed`() {
        val book = dir(
            "Title",
            file("vol1.cbz", "v1"),
            dir("bonus", file("001.jpg", "p1")),
        )
        val catalog = collectMangaCatalog(dir("Library", book))

        val entry = catalog.folders.single()
        assertEquals(MangaFolderKind.Mixed, entry.layout)
        assertEquals(2, entry.chapterCount)
        assertEquals(listOf("bonus", "vol1"), titles(book))
    }

    @Test
    fun `book root pages are a cover, not a chapter, when chapters exist`() {
        val book = dir(
            "Title",
            file("cover.jpg", "cover"),
            dir("Глава 1", file("001.jpg", "p1")),
        )

        assertEquals(listOf("Глава 1"), titles(book))
    }

    @Test
    fun `book root pages are a single chapter when nothing is nested`() {
        val book = dir("OneShot", file("001.jpg", "p1"), file("002.jpg", "p2"))

        val chapter = collectMangaChapters(book).single()
        assertEquals("OneShot", chapter.title)
        assertEquals(2, chapter.pages.size)
    }

    @Test
    fun `configured root pages produce no books`() {
        val catalog = collectMangaCatalog(dir("Library", file("001.jpg", "p1")))

        assertTrue(catalog.files.isEmpty())
        assertTrue(catalog.folders.isEmpty())
    }

    @Test
    fun `empty folders are skipped`() {
        val catalog = collectMangaCatalog(
            dir("Library", dir("Empty", dir("Nothing")), dir("EmptyTwo")),
        )

        assertTrue(catalog.folders.isEmpty())
    }

    @Test
    fun `volume folders are naturally ordered`() {
        val book = dir(
            "Title",
            dir("Vol 10", file("001.jpg")),
            dir("Vol 2", file("001.jpg")),
            dir("Vol 1", file("001.jpg")),
        )

        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), titles(book))
    }

    @Test
    fun `a tree deeper than the limit warns and stops that branch`() {
        var node = dir("Bottom", file("001.jpg"))
        repeat(14) { level ->
            node = dir("Level $level", node)
        }

        val scan = collectMangaCatalog(dir("Library", node))
        val warnings = mutableListOf<String>()
        val chapters = collectMangaChapters(node, warnings)

        assertTrue(warnings.any { it.contains("too deep") })
        assertTrue(chapters.isEmpty())
        assertTrue(scan.warnings.any { it.contains("too deep") })
    }

    @Test
    fun `first comic info is found at the root or inside a chapter folder`() {
        val atRoot = dir("Book", dir("Глава 1"), file("ComicInfo.xml", "root"))
        assertEquals("content://test/document/root", firstComicInfoUri(atRoot))

        val nested = dir(
            "Book",
            dir("Глава 1", file("ComicInfo.xml", "nested"), file("001.jpg")),
        )
        assertEquals("content://test/document/nested", firstComicInfoUri(nested))

        assertNull(firstComicInfoUri(dir("Book", dir("Глава 1", file("001.jpg")))))
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
