package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LocalBookFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogEntryTest {

    private val registryStore = mutableMapOf<String, String>()
    private val registry = LocalSafRegistry(object : LocalSafRegistryStore {
        override fun get(key: String): String? = registryStore[key]
        override fun put(key: String, value: String) {
            registryStore[key] = value
        }

        override fun all(): Map<String, String> = registryStore.toMap()

        override fun mergeAll(values: Map<String, String>) {
            registryStore.putAll(values)
        }
    })

    private val folderA = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
    private val folderB = "content://com.android.externalstorage.documents/tree/primary%3AManga"

    private fun file(uri: String, name: String, format: String, size: Long = 100, at: Long = 1) =
        ScannedLocalFile(uri, name, format, size, at)

    private fun scan(uri: String = folderA, name: String = "Books", files: List<ScannedLocalFile>) =
        LocalFolderScan(uri, name, files)

    @Test
    fun `scan flattens configured folders and keeps stable document identity`() {
        val one = "$folderA/document/primary%3ABooks%2Fone.epub"
        val two = "$folderA/document/primary%3ABooks%2Ftwo.fb2"
        val duplicate = "$folderA/document/primary%3ABooks%2Fone.epub"

        val entries = buildLocalCatalog(
            kind = "books",
            folders = listOf(
                scan(files = listOf(file(one, "one.epub", "EPUB", 10, 100), file(two, "two.fb2", "FB2", 20, 200))),
                scan(uri = "$folderA/nested", name = "Nested", files = listOf(file(duplicate, "one.epub", "EPUB"))),
            ),
        )

        assertEquals(listOf(one, two), entries.map { it.uri })
        assertEquals("one", entries.first().title)
        assertEquals("Books", entries.first().folderName)
        assertEquals(folderA, entries.first().folderUri)
        assertEquals(10L, entries.first().sizeBytes)
        assertEquals(100L, entries.first().lastModified)
        assertFalse(entries.first().isManga)
    }

    @Test
    fun `file added later appears on the next scan`() {
        val one = "$folderA/document/one.epub"
        val two = "$folderA/document/two.epub"

        val before = buildLocalCatalog("books", listOf(scan(files = listOf(file(one, "one.epub", "EPUB")))))
        val after = buildLocalCatalog(
            "books",
            listOf(scan(files = listOf(file(one, "one.epub", "EPUB"), file(two, "two.epub", "EPUB", at = 2)))),
        )

        assertEquals(listOf(one), before.map { it.uri })
        assertEquals(listOf(one, two), after.map { it.uri })
    }

    @Test
    fun `removing a folder removes its files from the catalog`() {
        val books = "$folderA/document/book.epub"
        val manga = "$folderB/document/book.cbz"
        val folders = listOf(
            scan(files = listOf(file(books, "book.epub", "EPUB"))),
            scan(uri = folderB, name = "Manga", files = listOf(file(manga, "book.cbz", "CBZ"))),
        )

        assertEquals(2, buildLocalCatalog("books", folders).size)
        assertEquals(
            listOf(books),
            buildLocalCatalog("books", folders.dropLast(1)).map { it.uri },
        )
    }

    @Test
    fun `imported file maps to its existing book and unimported file gets a catalog url`() {
        val entry = entry("$folderA/document/one.epub", "one.epub", "EPUB")
        val imported = ImportedLocalBook(
            bookUrl = "local://db-one",
            title = "Real Title",
            chapters = 12,
            coverUrl = "file:///cover",
        )

        val linked = entry.toCatalogBook(imported)
        assertEquals("local://db-one", linked.bookUrl)
        assertEquals("Real Title", linked.title)
        assertEquals(12, linked.chapters)
        assertEquals("file:///cover", linked.coverUrl)
        assertEquals("EPUB", linked.localFormat)
        assertFalse(linked.notImported)

        val catalogOnly = entry.toCatalogBook()
        assertEquals(localCatalogUrl(entry.uri), catalogOnly.bookUrl)
        assertEquals("one", catalogOnly.title)
        assertEquals("EPUB", catalogOnly.localFormat)
        assertTrue(catalogOnly.notImported)
        assertEquals(0, catalogOnly.chapters)
    }

    @Test
    fun `manga entry title is the folder name, ComicInfo title is ignored`() {
        val entry = LocalCatalogEntry(
            uri = "$folderB/document/folder",
            displayName = "Моё перерождение",
            format = "IMAGES",
            kind = "manga",
            folderUri = folderB,
            folderName = "Manga",
            entryType = LocalCatalogEntryType.Folder,
            mangaLayout = MangaFolderKind.ImageBook,
            documentId = "folder",
            embeddedTitle = "Глава 16",
            embeddedAuthor = "Author",
            coverUrl = "file:///cache/cover.jpg",
            chapterCount = 4,
        )

        val book = entry.toCatalogBook()

        assertTrue(book.notImported)
        assertEquals("Моё перерождение", book.title)
        assertEquals("file:///cache/cover.jpg", book.coverUrl)
        assertEquals(4, book.chapters)
        assertEquals("IMAGES", book.localFormat)
        assertEquals(localCatalogUrl(entry.uri), book.bookUrl)
    }

    @Test
    fun `manga file entry title is the file name without extension`() {
        val entry = LocalCatalogEntry(
            uri = "$folderB/document/vol1.cbz",
            displayName = "Том 1.cbz",
            format = "CBZ",
            kind = "manga",
            folderUri = folderB,
            folderName = "Manga",
            embeddedTitle = "Chapter 1",
        )

        assertEquals("Том 1", entry.title)
        assertEquals("Том 1", entry.toCatalogBook().title)
    }

    @Test
    fun `imported card keeps the embedded cover when no stored cover exists yet`() {
        // Direct open persists the cover, but the first catalogue render may
        // happen before it: the card must not lose the embedded cover.
        val entry = entry("$folderB/document/folder", "folder", "CBZ")
            .copy(coverUrl = "file:///cache/embedded.jpg")
        val imported = ImportedLocalBook(
            bookUrl = "local-file://abc",
            title = "Imported",
            chapters = 1,
            coverUrl = null,
        )

        assertEquals("file:///cache/embedded.jpg", entry.toCatalogBook(imported).coverUrl)
        assertEquals(
            "file:///cache/embedded.jpg",
            localCatalogPageForEntries(listOf(entry), mapOf(entry.uri to imported), CatalogFilters(), 1)
                .books.single().coverUrl,
        )
    }

    @Test
    fun `chapter fragment is one based including the last chapter`() {
        val url = "local-file://abc"
        assertEquals(1, localFileChapterIndex(localFileChapterUrl(url, 1)))
        assertEquals(346, localFileChapterIndex(localFileChapterUrl(url, 346)))
        assertNull(localFileChapterIndex(localFileChapterUrl(url, 0)))
        assertNull(localFileChapterIndex(url))
    }

    @Test
    fun `folder scan entries are marked as folders with layout and chapter count`() {
        val folder = ScannedLocalFolder(
            uri = "$folderB/document/folder",
            name = "My Manga",
            documentId = "folder",
            layout = MangaFolderKind.CbzSet,
            chapterCount = 7,
            primaryChildUri = "$folderB/document/first.cbz",
        )
        val scan = buildLocalCatalogScan(
            kind = "manga",
            folders = listOf(LocalFolderScan(folderB, "Manga", emptyList(), listOf(folder))),
        )

        val entry = scan.entries.single()
        assertTrue(entry.isFolder)
        assertEquals(LocalCatalogEntryType.Folder, entry.entryType)
        assertEquals(MangaFolderKind.CbzSet, entry.mangaLayout)
        assertEquals(7, entry.chapterCount)
        assertEquals("CBZ", entry.format)
        assertEquals("$folderB/document/first.cbz", entry.primaryChildUri)
    }

    @Test
    fun `catalog url round-trips unicode spaces and encoded saf ids`() {
        val document = "$folderA/document/primary%3ABooks%2F%D0%9C%D0%BE%D1%8F%20%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0.epub"

        registry.register(document)
        val url = localCatalogUrl(document)

        assertTrue(url.startsWith("local-file://"))
        assertFalse(url.contains("content://"))
        assertEquals(document, localFileDocumentUri(url, registry))
        assertEquals(url, localCatalogUrl(document))
    }

    @Test
    fun `catalog url is a short hex id with no reserved characters`() {
        val document = "$folderA/document/primary%3ABooks%2Fbook.epub"
        val url = localCatalogUrl(document)
        registry.register(document)

        val id = url.removePrefix("local-file://")
        assertTrue(id.length in 32..64)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(url.substringAfter("://").contains('%'))
        assertFalse(url.substringAfter("://").contains('#'))
        assertFalse(url.substringAfter("://").contains('+'))
        assertFalse(url.substringAfter("://").contains('='))
        assertTrue(url.length < 80)
        assertEquals(document, localFileDocumentUri(url, registry))
    }

    @Test
    fun `catalog cards use the registry-assigned id after a collision`() {
        val extended = "0123456789abcdef0123456789abcdef01234567"
        val entry = entry("$folderA/document/one.epub", "one.epub", "EPUB")
            .copy(catalogId = extended)

        assertEquals("local-file://$extended", entry.toCatalogBook().bookUrl)
    }

    @Test
    fun `catalog url is stable and different documents get different ids`() {
        val one = "$folderA/document/one.epub"
        val two = "$folderA/document/two.epub"

        assertEquals(localCatalogUrl(one), localCatalogUrl(one))
        assertTrue(localCatalogUrl(one) != localCatalogUrl(two))
        val id = registry.register(one)
        assertEquals(id, registry.register(one))
        assertEquals(one, registry.uriFor(id))
        assertEquals(id, registry.idFor(one))
    }

    @Test
    fun `import entry rejects other schemes malformed urls and traversal`() {
        assertNull(localFileDocumentUri("local://book/one", registry))
        assertNull(localFileDocumentUri("https://example.com/book", registry))
        assertNull(localFileDocumentUri("file:///tmp/book.epub", registry))
        assertNull(localFileDocumentUri("local-file://", registry))
        assertNull(localFileDocumentUri("local-file://content%3A%2F", registry))
        assertNull(localFileDocumentUri("local-file://hello", registry))
        assertNull(localFileDocumentUri(localCatalogUrl("$folderA/document/..%2Fsecret.epub"), registry))
        assertNull(localFileDocumentUri(localCatalogUrl("$folderA/document/%2E%2E%2Fsecret.epub"), registry))
    }

    @Test
    fun `import entry dedupes an already imported document uri`() {
        val document = "$folderA/document/one.epub"
        val file = LocalBookFileEntity(
            bookId = 7,
            uri = document,
            displayName = "one.epub",
            format = "EPUB",
            mime = "application/epub+zip",
            sizeBytes = 10,
            sha256 = "sha",
            importedAt = 1,
        )
        val book = BookEntity(
            bookId = 7,
            installId = "local-books",
            url = "local://seven",
            title = "Imported Title",
            author = "Unknown",
            coverUrl = null,
            contentType = "Novel",
            rating = 0.0,
            shelfId = "reading",
            totalChapters = 4,
            inLibrary = true,
            addedAt = 1,
            updatedAt = 1,
            lastReadAt = null,
            lastChapterId = null,
            lastChapterIndex = null,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = "[]",
        )

        val existing = existingLocalCatalogImport(document, file, book)

        assertEquals("local://seven", existing?.bookUrl)
        assertEquals("Imported Title", existing?.title)
        assertTrue(existing?.duplicate == true)
        assertNull(existingLocalCatalogImport("$folderA/document/other.epub", file, book))
        assertNull(existingLocalCatalogImport(document, null, book))
        assertNull(existingLocalCatalogImport(document, file, null))
    }

    @Test
    fun `local filters search formats and kinds in memory`() {
        val epub = entry("$folderA/document/one.epub", "one.epub", "EPUB")
        val fb2 = entry("$folderA/document/two.fb2", "two.fb2", "FB2")
        val entries = listOf(epub, fb2)
        val imported = mapOf(epub.uri to ImportedLocalBook("local://one", "Imported One", 3))

        val page = localCatalogPageForEntries(entries, imported, CatalogFilters(), page = 1)
        assertEquals(listOf("local://one", localCatalogUrl(fb2.uri)), page.books.map { it.bookUrl })
        assertFalse(page.hasMore)

        val byFormat = localCatalogPageForEntries(
            entries,
            imported,
            CatalogFilters(genres = setOf("EPUB")),
            page = 1,
        )
        assertEquals(listOf("local://one"), byFormat.books.map { it.bookUrl })

        val byKind = localCatalogPageForEntries(
            entries,
            imported,
            CatalogFilters(genres = setOf("books")),
            page = 1,
        )
        assertEquals(2, byKind.books.size)

        // Plugin genres do not exist on local files; an unknown value matches
        // nothing rather than being silently ignored.
        assertTrue(
            localCatalogPageForEntries(
                entries,
                imported,
                CatalogFilters(genres = setOf("Fantasy")),
                page = 1,
            ).books.isEmpty(),
        )

        val query = localCatalogPageForEntries(
            entries,
            imported,
            CatalogFilters(query = "two"),
            page = 1,
        )
        assertEquals(listOf(localCatalogUrl(fb2.uri)), query.books.map { it.bookUrl })

        val sorted = localCatalogPageForEntries(
            entries,
            imported,
            CatalogFilters(sort = CatalogSort.Title, descending = false),
            page = 1,
        )
        assertEquals(listOf("Imported One", "two"), sorted.books.map { it.title })

        assertEquals(
            listOf(localCatalogUrl(fb2.uri)),
            localCatalogSearch(entries, imported, "two").map { it.bookUrl },
        )
    }

    @Test
    fun `page paginates scan results`() {
        val entries = (1..5).map { entry("$folderA/document/$it.epub", "$it.epub", "EPUB") }

        val first = localCatalogPageForEntries(entries, emptyMap(), CatalogFilters(), page = 1, pageSize = 2)
        val last = localCatalogPageForEntries(entries, emptyMap(), CatalogFilters(), page = 3, pageSize = 2)

        assertEquals(2, first.books.size)
        assertTrue(first.hasMore)
        assertEquals(1, last.books.size)
        assertFalse(last.hasMore)
    }

    private fun entry(uri: String, name: String, format: String) = LocalCatalogEntry(
        uri = uri,
        displayName = name,
        format = format,
        kind = "books",
        folderUri = folderA,
        folderName = "Books",
    )
}
