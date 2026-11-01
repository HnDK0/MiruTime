package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.app.features.translation.data.TranslationMode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookEpubExporterTest {

    private class FakeProvider(
        private val details: BookDetails,
        private val contents: Map<Int, ChapterContent>,
    ) : BookDataProvider {
        var chapterContentCalls = 0
        override suspend fun book(bookUrl: String): BookDetails = details
        override suspend fun ensureHistoryBook(bookUrl: String) = Unit
        override suspend fun chapters(bookUrl: String): List<BookChapter> = details.chapters
        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? {
            chapterContentCalls++
            return contents[chapterIndex]
        }

        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean) =
            MembershipResult(success = true)
        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private fun details(chapters: List<BookChapter>, title: String = "Test Book") = BookDetails(
        bookUrl = "test://book",
        title = title,
        source = "Test",
        author = "Author",
        originLanguage = "English",
        contentType = ContentType.Novel,
        rating = 0f,
        ongoing = true,
        updatedLabel = "",
        totalChapters = chapters.size,
        readCount = 0,
        readMinutes = 0,
        ttsMinutes = 0,
        inLibrary = true,
        shelfId = "reading",
        genres = emptyList(),
        description = "A description",
        sourceUrl = "",
        authUrl = null,
        chapters = chapters,
        reviews = emptyList(),
        coverUrl = null,
    )

    private fun chapter(index: Int) = BookChapter(
        index = index,
        title = "Chapter $index",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    )

    private fun content(index: Int, html: String) = ChapterContent(
        bookUrl = "test://book",
        chapterIndex = index,
        title = "Chapter $index",
        html = html,
        chapterUrl = "test://book/chapter-$index",
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `file name is sanitized`() {
        assertEquals("A_B_ C_.epub", epubFileName("A/B: C?"))
        assertEquals("My Book.epub", epubFileName("  My   Book  "))
        assertEquals("book.epub", epubFileName("   "))
        assertEquals("book.epub", epubFileName("..."))
    }

    @Test
    fun `translated blocks replace text and keep images in place`() {
        val html = "<h2>Title</h2><p>One</p><p><img src=\"pic.png\" alt=\"Fig\"/></p><p>Two</p>"
        val result = translatedChapterHtml(html, "chapter", mapOf(1 to "Uno", 3 to "Dos"))

        assertTrue(result.contains("<h2>Title</h2>"))
        assertTrue(result.contains("<p>Uno</p>"))
        assertTrue(result.contains("""<img src="pic.png" alt="Fig"/>"""))
        assertTrue(result.contains("<p>Dos</p>"))
        assertFalse(result.contains("One"))
        assertFalse(result.contains("Two"))
    }

    @Test
    fun `missing translation falls back to original text`() {
        val html = "<p>One</p><p>Two</p>"
        val result = translatedChapterHtml(html, "chapter", mapOf(1 to "Dos"))

        assertTrue(result.contains("<p>One</p>"))
        assertTrue(result.contains("<p>Dos</p>"))
        // No cached blocks at all: the original HTML is returned untouched.
        assertEquals(html, translatedChapterHtml(html, "chapter", emptyMap()))
    }

    @Test
    fun `relative image sources resolve against the chapter url`() {
        assertEquals(
            """<p><img src="https://site/book/images/a.png"/></p>""",
            resolveImageSources(
                """<p><img src="images/a.png"/></p>""",
                "https://site/book/chapter-1",
            ),
        )
        assertEquals(
            """<p><img src="https://cdn/a.png"/></p>""",
            resolveImageSources("""<p><img src="//cdn/a.png"/></p>""", "https://site/book"),
        )
        assertEquals(
            """<p><img src="data:image/png;base64,AA"/></p>""",
            resolveImageSources("""<p><img src="data:image/png;base64,AA"/></p>""", "https://site/book"),
        )
    }

    @Test
    fun `local file image loader reads file urls only`() {
        val file = File.createTempFile("epub-image-", ".png").apply { deleteOnExit() }
        file.writeBytes(byteArrayOf(1, 2, 3))

        val bytes = readFileUrlBytes(file.toURI().toString())
        assertTrue(bytes != null && bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertNull(readFileUrlBytes("https://example.com/pic.png"))
        assertNull(readFileUrlBytes("file:///definitely/not/here.png"))
    }

    @Test
    fun `export writes mimetype first and all chapters`() {
        val chapters = listOf(chapter(1), chapter(2))
        val provider = FakeProvider(
            details(chapters, title = "Test & Book"),
            mapOf(
                1 to content(1, "<p>Hello &amp; bye</p>"),
                2 to content(2, "<p>World</p>"),
            ),
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        val out = ByteArrayOutputStream()

        val result = runBlocking {
            BookEpubExporter(provider).export(
                bookUrl = "test://book",
                includeTranslation = false,
                outputStream = out,
                onProgress = { done, total -> progress += done to total },
            )
        }

        val success = result as EpubExportResult.Success
        assertEquals(2, success.chaptersWritten)
        assertEquals(listOf(1 to 2, 2 to 2), progress)

        val entries = unzip(out.toByteArray())
        assertEquals("mimetype", entries.keys.first())
        val firstEntry = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            val entry = zip.nextEntry
            assertEquals(ZipEntry.STORED, entry.method)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals("application/epub+zip", firstEntry)
        assertTrue(entries.containsKey("OEBPS/content.opf"))
        assertTrue(entries.containsKey("OEBPS/nav.xhtml"))
        assertTrue(entries.containsKey("OEBPS/toc.ncx"))
        assertTrue(entries.containsKey("OEBPS/chapter-001.xhtml"))
        assertTrue(entries.containsKey("OEBPS/chapter-002.xhtml"))
        assertTrue(
            entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
                .contains("<p>Hello &amp; bye</p>"),
        )
    }

    @Test
    fun `persisted chapter body wins over provider content`() = runBlocking {
        val provider = FakeProvider(
            details(listOf(chapter(1))),
            mapOf(1 to content(1, "<p>Provider text</p>")),
        )
        val out = ByteArrayOutputStream()

        val result = BookEpubExporter(
            provider,
            chapterBodyLoader = { _, index ->
                if (index == 1) {
                    "<p>DB text</p><p><img src=\"file:///covers/1/cover\"/></p>"
                } else {
                    null
                }
            },
        ).export("test://book", includeTranslation = false, outputStream = out)

        assertTrue(result is EpubExportResult.Success)
        assertEquals(0, provider.chapterContentCalls)
        val chapterHtml = unzip(out.toByteArray())
            .getValue("OEBPS/chapter-001.xhtml")
            .toString(Charsets.UTF_8)
        assertTrue(chapterHtml.contains("<p>DB text</p>"))
        assertTrue(chapterHtml.contains("file:///covers/1/cover"))
        assertFalse(chapterHtml.contains("Provider text"))
    }

    @Test
    fun `include translation uses only supplied ready blocks`() {
        val provider = FakeProvider(
            details(listOf(chapter(1))),
            mapOf(1 to content(1, "<p>Original</p>")),
        )
        val asked = mutableListOf<Triple<String, String, String>>()

        val translated = runBlocking {
            val out = ByteArrayOutputStream()
            val result = BookEpubExporter(
                provider,
                translationsFor = { bookUrl, chapterUrl, html ->
                    asked += Triple(bookUrl, chapterUrl, html)
                    mapOf(0 to "Перевод")
                },
            ).export("test://book", includeTranslation = true, outputStream = out)
            assertTrue(result is EpubExportResult.Success)
            unzip(out.toByteArray()).getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        }

        assertEquals(
            listOf(Triple("test://book", "test://book/chapter-1", "<p>Original</p>")),
            asked,
        )
        assertTrue(translated.contains("<p>Перевод</p>"))
        assertFalse(translated.contains("Original"))

        val original = runBlocking {
            val out = ByteArrayOutputStream()
            BookEpubExporter(provider)
                .export("test://book", includeTranslation = false, outputStream = out)
            unzip(out.toByteArray()).getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        }
        assertTrue(original.contains("<p>Original</p>"))
    }

    @Test
    fun `resolver can supply persisted maps per chapter`() {
        val chapters = listOf(chapter(1), chapter(2))
        val provider = FakeProvider(
            details(chapters),
            mapOf(
                1 to content(1, "<p>First</p>"),
                2 to content(2, "<p>Second</p>"),
            ),
        )
        val out = ByteArrayOutputStream()

        val result = runBlocking {
            BookEpubExporter(
                provider,
                translationsFor = { _, chapterUrl, html ->
                    if (chapterUrl.endsWith("-2") && html.contains("Second")) {
                        mapOf(0 to "Persisted")
                    } else {
                        null
                    }
                },
            ).export("test://book", includeTranslation = true, outputStream = out)
        }

        assertTrue(result is EpubExportResult.Success)
        val entries = unzip(out.toByteArray())
        val first = entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        val second = entries.getValue("OEBPS/chapter-002.xhtml").toString(Charsets.UTF_8)
        assertTrue(first.contains("<p>First</p>"))
        assertTrue(second.contains("<p>Persisted</p>"))
        assertFalse(second.contains("Second"))
    }

    @Test
    fun `resolver sees original html while output images stay resolved`() {
        val raw = "<p>Original</p><p><img src=\"images/p.png\"/></p>"
        val provider = FakeProvider(
            details(listOf(chapter(1))),
            mapOf(1 to content(1, raw)),
        )
        var seenHtml: String? = null
        val out = ByteArrayOutputStream()

        val result = runBlocking {
            BookEpubExporter(
                provider,
                translationsFor = { _, _, html ->
                    seenHtml = html
                    mapOf(0 to "Перевод")
                },
            ).export("test://book", includeTranslation = true, outputStream = out)
        }

        assertTrue(result is EpubExportResult.Success)
        // The cache key must use the same source html ReaderScreen parses.
        assertEquals(raw, seenHtml)
        val chapter = unzip(out.toByteArray()).getValue("OEBPS/chapter-001.xhtml")
            .toString(Charsets.UTF_8)
        assertTrue(chapter.contains("<p>Перевод</p>"))
        assertTrue(chapter.contains("""<img src="test://book/images/p.png" alt=""/>"""))
    }

    @Test
    fun `cached request matches reader key inputs`() {
        val bookUrl = "https://example.com/book/1"
        val chapterUrl = "https://example.com/book/1/chapter/7"
        val html = "<h2>Title</h2><p>One &amp; two</p><p><img src=\"i.png\"/></p>"
        val request = cachedTranslationRequest(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            html = html,
            mode = TranslationMode(source = "en", target = "ru", provider = "GOOGLE_PA"),
            prompt = PromptSpec("Prompt", version = "v1"),
        )

        assertNotNull(request)
        requireNotNull(request)
        assertEquals(bookUrl.hashCode().toLong(), request.bookId)
        assertEquals(bookUrl, request.bookUrl)
        assertEquals(chapterUrl, request.chapterUrl)
        assertEquals("en", request.source?.value)
        assertEquals("ru", request.target.value)
        assertEquals(ProviderId.GOOGLE_PA, request.provider)
        assertEquals("Prompt", request.prompt?.text)
        assertEquals("reader-1", request.revision.pipelineVersion)
        assertEquals(
            ChapterTranslationPlanner.sourceHash(ReaderHtmlParser.parseDocument(html, chapterUrl)),
            request.revision.sourceHash,
        )
        // Text blocks only: h2 and p are units, the image block is not.
        assertEquals(listOf("0", "1"), request.units.map { it.id })

        assertNull(
            cachedTranslationRequest(
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                html = html,
                mode = TranslationMode(source = "ru", target = "ru", provider = "GOOGLE_PA"),
                prompt = null,
            ),
        )
        assertNull(
            cachedTranslationRequest(
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                html = html,
                mode = TranslationMode(source = "en", target = "ru", provider = "NOPE"),
                prompt = null,
            ),
        )
    }

    @Test
    fun `missing chapter is skipped without failing the export`() {
        val chapters = listOf(chapter(1), chapter(2))
        val provider = FakeProvider(
            details(chapters),
            mapOf(1 to content(1, "<p>Only one</p>")),
        )
        val out = ByteArrayOutputStream()

        val result = runBlocking {
            BookEpubExporter(provider).export("test://book", false, out)
        }

        val success = result as EpubExportResult.Success
        assertEquals(1, success.chaptersWritten)
        val entries = unzip(out.toByteArray())
        assertTrue(entries.containsKey("OEBPS/chapter-001.xhtml"))
        assertFalse(entries.containsKey("OEBPS/chapter-002.xhtml"))
    }

    @Test
    fun `empty chapter list returns no chapters`() {
        val provider = FakeProvider(details(emptyList()), emptyMap())
        val result = runBlocking {
            BookEpubExporter(provider).export("test://book", false, ByteArrayOutputStream())
        }
        assertEquals(EpubExportResult.NoChapters, result)
    }
}
