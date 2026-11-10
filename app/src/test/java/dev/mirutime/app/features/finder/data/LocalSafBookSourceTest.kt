package dev.mirutime.app.features.finder.data

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSafBookSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)
    private val tree = "content://test/tree/root"
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

    private class FakeResolver : SafResolver {
        val documents = mutableMapOf<String, SafDocument>()
        val children = mutableMapOf<String, List<SafChild>>()
        val files = mutableMapOf<String, File>()
        var revoked = false

        override suspend fun document(documentUri: String): SafDocument {
            if (revoked) throw LocalSafException(SafFailure.PermissionDenied, "Access revoked")
            return documents[documentUri]
                ?: throw LocalSafException(SafFailure.NotFound, "Missing $documentUri")
        }

        override suspend fun children(treeUri: String, documentId: String): List<SafChild> {
            if (revoked) throw LocalSafException(SafFailure.PermissionDenied, "Access revoked")
            return children[documentId].orEmpty()
        }

        override suspend fun openInput(documentUri: String): InputStream? =
            files[documentUri]?.inputStream()
    }

    // The catalogue metadata cache owns the cover file; the direct reader must
    // be able to read it back from that cache.
    private class FakeMetadata(
        override val root: File,
        private val coverUrl: String?,
    ) : LocalCatalogMetadataProvider {
        override suspend fun enrich(
            entries: List<LocalCatalogEntry>,
        ): List<LocalCatalogEntry> = entries.map { it.copy(coverUrl = it.coverUrl ?: coverUrl) }
    }

    private fun source(
        resolver: FakeResolver,
        metadata: LocalCatalogMetadataProvider? = null,
    ): LocalSafBookSource = LocalSafBookSource(
        resolver = resolver,
        cache = LocalSafCache(
            root = temp.newFolder("cache-${System.nanoTime()}"),
            open = { resolver.openInput(it) },
        ),
        registry = registry,
        metadataCache = metadata,
    )

    private fun addFile(
        resolver: FakeResolver,
        id: String,
        name: String,
        file: File,
    ): String {
        val uri = "$tree/document/$id"
        resolver.documents[uri] = SafDocument(
            uri = uri,
            documentId = id,
            name = name,
            mime = "application/octet-stream",
            sizeBytes = file.length(),
            lastModified = 1,
            isDirectory = false,
            treeUri = tree,
        )
        resolver.files[uri] = file
        registry.register(uri)
        return uri
    }

    private fun addFolder(resolver: FakeResolver, id: String, name: String, kids: List<SafChild>): String {
        val uri = "$tree/document/$id"
        resolver.documents[uri] = SafDocument(
            uri = uri,
            documentId = id,
            name = name,
            mime = "vnd.android.document/directory",
            sizeBytes = null,
            lastModified = 10,
            isDirectory = true,
            treeUri = tree,
        )
        resolver.children[id] = kids
        registry.register(uri)
        return uri
    }

    private fun child(
        id: String,
        name: String,
        file: File? = null,
        folder: Boolean = false,
    ): SafChild = SafChild(
        uri = "$tree/document/$id",
        documentId = id,
        name = name,
        sizeBytes = file?.length(),
        lastModified = 10,
        isDirectory = folder,
    )

    private fun zip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun epub(): File {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val entries = mutableListOf<Pair<String, ByteArray>>()
        repeat(2) { offset ->
            val number = offset + 1
            manifest.append("<item id=\"ch$number\" href=\"ch$number.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"ch$number\"/>")
            entries += "OEBPS/ch$number.xhtml" to
                "<html><body><p>Chapter $number body.</p></body></html>".encodeToByteArray()
        }
        val file = temp.newFile("saf-book.epub")
        zip(
            file,
            listOf(
                "META-INF/container.xml" to
                    "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>"
                        .encodeToByteArray(),
                "OEBPS/content.opf" to """
                    <package><metadata>
                    <dc:title>Saf Book</dc:title><dc:creator>Ann</dc:creator>
                    <dc:description>Desc</dc:description>
                    <meta name="cover" content="cover"/>
                    </metadata><manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    $manifest
                    <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    </manifest><spine toc="ncx">$spine</spine></package>
                """.trimIndent().encodeToByteArray(),
                "OEBPS/toc.ncx" to (
                    "<ncx><navMap>" +
                        "<navPoint><navLabel><text>One</text></navLabel><content src=\"ch1.xhtml\"/></navPoint>" +
                        "<navPoint><navLabel><text>Two</text></navLabel><content src=\"ch2.xhtml\"/></navPoint>" +
                        "</navMap></ncx>"
                    ).encodeToByteArray(),
                "OEBPS/cover.jpg" to jpeg,
            ) + entries,
        )
        return file
    }

    private fun fb2(): File {
        val file = temp.newFile("saf-book.fb2")
        file.writeText(
            """
            <FictionBook><description><title-info>
            <book-title>FB2 Saf</book-title><author><first-name>Boris</first-name></author>
            <annotation>FB2 Desc</annotation></title-info></description><body>
            <section><title><p>One</p></title><p>First body.</p></section>
            <section><title><p>Two</p></title><p>Second body.</p></section>
            </body></FictionBook>
            """.trimIndent(),
        )
        return file
    }

    private fun cbz(id: String, title: String, pages: List<Pair<String, ByteArray>>): File {
        val file = temp.newFile("$id.cbz")
        zip(
            file,
            listOf(
                "ComicInfo.xml" to "<ComicInfo><Title>$title</Title></ComicInfo>".encodeToByteArray(),
            ) + pages,
        )
        return file
    }

    @Test
    fun `epub direct source reads chapters and body without importing`() = runTest {
        val resolver = FakeResolver()
        val uri = addFile(resolver, "epub", "saf-book.epub", epub())
        val source = source(resolver)

        val book = source.book(localCatalogUrl(uri))

        assertEquals("Saf Book", book.title)
        assertEquals("Ann", book.author)
        assertEquals("Desc", book.description)
        assertEquals(2, book.chapterCount)
        assertEquals(
            listOf(localFileChapterUrl(book.bookUrl, 1), localFileChapterUrl(book.bookUrl, 2)),
            source.chapters(book.bookUrl).map { it.url },
        )
        assertTrue(source.chapterContent(book.bookUrl, 1).html.contains("Chapter 1 body."))
        assertTrue(source.chapterContent(book.bookUrl, 2).html.contains("Chapter 2 body."))
        assertNotNull("cover url: ${book.coverUrl}", book.coverUrl)
        assertEquals("jpg", coverImageExtension(source.coverBytes(book.bookUrl)!!))
    }

    @Test
    fun `fb2 direct source reads section chapters`() = runTest {
        val resolver = FakeResolver()
        val uri = addFile(resolver, "fb2", "saf-book.fb2", fb2())
        val source = source(resolver)

        val book = source.book(localCatalogUrl(uri))

        assertEquals("FB2 Saf", book.title)
        assertEquals("Boris", book.author)
        assertEquals(2, book.chapterCount)
        assertEquals(listOf("One", "Two"), source.chapters(book.bookUrl).map { it.title })
        assertTrue(source.chapterContent(book.bookUrl, 2).html.contains("Second body."))
    }

    @Test
    fun `cbz direct source is named after its file and has one chapter`() = runTest {
        val resolver = FakeResolver()
        val file = cbz(
            "comic",
            "CBZ Title",
            listOf("010.png" to png, "002.png" to png),
        )
        val uri = addFile(resolver, "cbz", "comic.cbz", file)
        val source = source(resolver)

        val book = source.book(localCatalogUrl(uri))

        assertEquals("comic", book.title)
        assertTrue(book.isManga)
        assertEquals(1, book.chapterCount)
        assertEquals(listOf("comic"), source.chapters(book.bookUrl).map { it.title })
        val html = source.chapterContent(book.bookUrl, 1).html
        val first = html.indexOf("00000.png")
        val second = html.indexOf("00001.png")
        assertTrue(first in 0..<second)
    }

    @Test
    fun `folder with one cbz is one book with one chapter`() = runTest {
        val resolver = FakeResolver()
        val file = cbz("solo", "Solo Chapter", listOf("001.png" to png))
        addFile(resolver, "solo", "only.cbz", file)
        val folderUri = addFolder(
            resolver,
            "solo-folder",
            "Solo Folder",
            listOf(child("solo", "only.cbz", file)),
        )
        val source = source(resolver)

        val book = source.book(localCatalogUrl(folderUri))

        // NoveLA parity: the folder is the book, the CBZ is its chapter.
        assertEquals("Solo Folder", book.title)
        assertTrue(book.isManga)
        assertEquals(1, book.chapterCount)
        val chapters = source.chapters(book.bookUrl)
        assertEquals(listOf("only"), chapters.map { it.title })
        assertTrue(source.chapterContent(book.bookUrl, 1).html.contains("<img"))
    }

    @Test
    fun `cbz set and image folders group chapters by natural order`() = runTest {
        val resolver = FakeResolver()
        val one = cbz("one", "One", listOf("001.png" to png))
        val two = cbz("two", "Two", listOf("001.png" to png))
        addFile(resolver, "one", "vol2.cbz", one)
        addFile(resolver, "two", "vol10.cbz", two)
        val setUri = addFolder(
            resolver,
            "set",
            "Set",
            listOf(child("two", "vol10.cbz", two), child("one", "vol2.cbz", one)),
        )
        val setSource = source(resolver)
        val setBook = setSource.book(localCatalogUrl(setUri))
        assertEquals(2, setBook.chapterCount)
        assertEquals(listOf("vol2", "vol10"), setSource.chapters(setBook.bookUrl).map { it.title })
        assertTrue(setSource.chapterContent(setBook.bookUrl, 1).html.contains("<img"))

        val imageFile = temp.newFile("page.png").apply { writeBytes(png) }
        addFile(resolver, "img1", "001.png", imageFile)
        addFile(resolver, "img2", "002.png", imageFile)
        addFolder(resolver, "ch1", "ch1", listOf(child("img1", "001.png", imageFile)))
        addFolder(resolver, "ch2", "ch2", listOf(child("img2", "002.png", imageFile)))
        val imageUri = addFolder(
            resolver,
            "images",
            "Images",
            listOf(
                child("ch2", "ch2", folder = true),
                child("ch1", "ch1", folder = true),
            ),
        )
        val imageSource = source(resolver)
        val imageBook = imageSource.book(localCatalogUrl(imageUri))
        assertEquals(2, imageBook.chapterCount)
        assertEquals(listOf("ch1", "ch2"), imageSource.chapters(imageBook.bookUrl).map { it.title })
        val imageHtml = imageSource.chapterContent(imageBook.bookUrl, 2).html
        assertTrue(imageHtml.contains("content://test/tree/root/document/img2"))
    }

    @Test
    fun `nested volume folders are chapters of one book`() = runTest {
        val resolver = FakeResolver()
        val page = temp.newFile("001.png").apply { writeBytes(png) }
        addFile(resolver, "page", "001.png", page)
        addFolder(resolver, "chapter", "Глава 16", listOf(child("page", "001.png", page)))
        addFolder(resolver, "volume", "Vol 1", listOf(child("chapter", "Глава 16", folder = true)))
        val bookUri = addFolder(
            resolver,
            "book",
            "Моё перерождение",
            listOf(child("volume", "Vol 1", folder = true)),
        )
        val source = source(resolver)

        val book = source.book(localCatalogUrl(bookUri))

        assertEquals("Моё перерождение", book.title)
        assertEquals(1, book.chapterCount)
        assertEquals(
            listOf("Vol 1 / Глава 16"),
            source.chapters(book.bookUrl).map { it.title },
        )
    }

    @Test
    fun `a mixed book keeps archive and image chapters`() = runTest {
        val resolver = FakeResolver()
        val page = temp.newFile("mixed.png").apply { writeBytes(png) }
        addFile(resolver, "mixed-page", "001.png", page)
        addFolder(
            resolver,
            "mixed-bonus",
            "bonus",
            listOf(child("mixed-page", "001.png", page)),
        )
        val archive = cbz("mixed-vol", "Vol 1", listOf("001.png" to png))
        addFile(resolver, "mixed-vol", "vol1.cbz", archive)
        val bookUri = addFolder(
            resolver,
            "mixed-book",
            "Mixed",
            listOf(
                child("mixed-bonus", "bonus", folder = true),
                child("mixed-vol", "vol1.cbz", archive),
            ),
        )
        val source = source(resolver)

        val book = source.book(localCatalogUrl(bookUri))

        assertEquals("Mixed", book.title)
        assertEquals(2, book.chapterCount)
        assertEquals(listOf("bonus", "vol1"), source.chapters(book.bookUrl).map { it.title })
        assertTrue(source.chapterContent(book.bookUrl, 1).html.contains("<img"))
        assertTrue(source.chapterContent(book.bookUrl, 2).html.contains("<img"))
    }

    @Test
    fun `revoked access is a typed failure and cache keeps one archive per identity`() = runTest {
        val resolver = FakeResolver()
        val uri = addFile(resolver, "epub", "saf-book.epub", epub())
        val source = source(resolver)
        val book = source.book(localCatalogUrl(uri))
        val archiveFirst = source.book(book.bookUrl)
        assertEquals(book.cacheHash, archiveFirst.cacheHash)

        resolver.revoked = true
        try {
            source.book(localCatalogUrl(uri))
            fail("expected a typed SAF failure")
        } catch (e: LocalSafException) {
            assertEquals(SafFailure.PermissionDenied, e.failure)
        }
    }

    @Test
    fun `a cover from the metadata cache is read back`() = runTest {
        val resolver = FakeResolver()
        val uri = addFile(
            resolver,
            "cbz",
            "comic.cbz",
            cbz("comic", "Comic", listOf("001.png" to png)),
        )
        val metadataRoot = temp.newFolder("local-catalog")
        val cover = File(metadataRoot, "cover.png").apply { writeBytes(png) }
        val source = source(resolver, FakeMetadata(metadataRoot, cover.toURI().toString()))

        val book = source.book(localCatalogUrl(uri))

        assertEquals(cover.toURI().toString(), book.coverUrl)
        assertArrayEquals(png, source.coverBytes(book.bookUrl))
    }

    @Test
    fun `a cover outside the app caches is not read`() = runTest {
        val resolver = FakeResolver()
        val uri = addFile(
            resolver,
            "cbz",
            "comic.cbz",
            cbz("comic", "Comic", listOf("001.png" to png)),
        )
        val metadataRoot = temp.newFolder("local-catalog")
        val outside = temp.newFile("outside.png").apply { writeBytes(png) }
        val source = source(resolver, FakeMetadata(metadataRoot, outside.toURI().toString()))

        val book = source.book(localCatalogUrl(uri))

        assertNull(source.coverBytes(book.bookUrl))
    }

    @Test
    fun `cache eviction removes pages and archives`() = runTest {
        val root = temp.newFolder("evict")
        val file = temp.newFile("evict.epub").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cache = LocalSafCache(root = root, open = { file.inputStream() })
        val archive = cache.archive("content://test/doc", 1, 3, "epub")
        assertTrue(archive.isFile)
        assertEquals(archive, cache.archive("content://test/doc", 1, 3, "epub"))
        val changed = cache.archive("content://test/doc", 2, 3, "epub")
        assertTrue(changed.isFile)
        assertTrue(changed.name != archive.name)
        val page = cache.writePage("hash", 1, 1, "png", png)
        assertTrue(page.isFile)
        assertNotNull(page)
        cache.cleanup(limitBytes = 0)
        assertTrue(!archive.exists())
        assertTrue(!page.exists())
    }
}
