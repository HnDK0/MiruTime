package dev.mirutime.core.local

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubExporterTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)

    private fun exportToBytes(
        title: String = "Test Book",
        language: String = "en",
        chapters: List<Pair<String, String>>,
        coverBytes: ByteArray? = null,
        description: String? = null,
        creator: String? = null,
        subjects: List<String> = emptyList(),
        imageByteLoader: suspend (String) -> ByteArray? = { null },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        export(
            out, title, language, chapters, coverBytes, description, creator, subjects,
            imageByteLoader = imageByteLoader, onProgress = onProgress
        )
        out.toByteArray()
    }

    private fun exportStreamingToBytes(
        totalChapters: Int,
        chapterLoader: suspend (Int, Int) -> List<Pair<String, String>>,
        imageByteLoader: suspend (String) -> ByteArray? = { null },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        exportStreaming(
            out, "Test Book", "en", totalChapters, chapterLoader,
            imageByteLoader = imageByteLoader, onProgress = onProgress
        )
        out.toByteArray()
    }

    /** Unzips preserving archive order — the first key must be "mimetype". */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /** Unzips preserving raw bytes (cover and inline image comparison). */
    private fun unzipRaw(bytes: ByteArray): Map<String, ByteArray> {
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

    private fun paragraphContent(chapterXhtml: String): String {
        val match = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(chapterXhtml)
        assertNotNull(match, "chapter must contain a <p> element")
        return match.groupValues[1]
    }

    @Test
    fun `mimetype is first and stored and structure is valid`() {
        val bytes = exportToBytes(
            chapters = listOf(
                "Chapter One" to "Para 1a\n\nPara 1b",
                "Chapter Two" to "Para 2a\n\nPara 2b",
            )
        )
        val entries = unzip(bytes)

        assertEquals("mimetype", entries.keys.first())
        val mimetype = ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry
            assertEquals(ZipEntry.STORED, entry.method)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals("application/epub+zip", mimetype)

        for (name in listOf(
            "META-INF/container.xml",
            "OEBPS/content.opf",
            "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx",
            "OEBPS/chapter-001.xhtml",
            "OEBPS/chapter-002.xhtml",
        )) {
            assertTrue(entries.containsKey(name), "missing entry $name")
        }

        val opf = entries.getValue("OEBPS/content.opf")
        assertEquals(2, Regex("<itemref idref=").findAll(opf).count())
        assertTrue(opf.contains("""properties="nav""""))
        assertTrue(opf.contains("dcterms:modified"))
        assertTrue(opf.contains("<dc:language>en</dc:language>"))
        // NCX must be in both the manifest and the spine, otherwise legacy
        // readers report "ncx file missing".
        assertTrue(opf.contains("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""))
        assertTrue(opf.contains("""<spine toc="ncx">"""))

        val ncx = entries.getValue("OEBPS/toc.ncx")
        assertTrue(ncx.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(ncx.contains("xmlns=\"http://www.daisy.org/z3986/2005/ncx/\""))
        assertTrue(ncx.contains("<docTitle><text>Test Book</text></docTitle>"))
        assertEquals(2, Regex("<navPoint ").findAll(ncx).count())
        assertTrue(ncx.contains("""<content src="chapter-002.xhtml"/>"""))

        // exactly one bare xmlns (XHTML) plus the declared epub prefix.
        val nav = entries.getValue("OEBPS/nav.xhtml")
        assertTrue(nav.contains("""xmlns:epub="http://www.idpf.org/2007/ops""""))
        assertEquals(1, Regex("""<html[^>]*xmlns=""").findAll(nav).count())

        for (name in listOf("OEBPS/chapter-001.xhtml", "OEBPS/chapter-002.xhtml")) {
            val chapter = entries.getValue(name)
            assertTrue(chapter.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
            assertTrue(chapter.contains("xmlns=\"http://www.w3.org/1999/xhtml\""))
            assertEquals(2, Regex("<p>").findAll(chapter).count())
        }
    }

    @Test
    fun `special characters are xml escaped`() {
        val chapter = unzip(
            exportToBytes(chapters = listOf("Esc" to "5 < 10 & 7 > 3 \"quoted\""))
        ).getValue("OEBPS/chapter-001.xhtml")
        val paragraph = paragraphContent(chapter)

        assertTrue(paragraph.contains("&lt;"))
        assertTrue(paragraph.contains("&amp;"))
        assertTrue(paragraph.contains("&gt;"))
        assertTrue(paragraph.contains("&quot;"))
        assertFalse(paragraph.contains("<"))
        assertFalse(paragraph.contains(">"))
        assertFalse(Regex("&(?!amp;|lt;|gt;|quot;|apos;)").containsMatchIn(paragraph))
    }

    @Test
    fun `html entities are unescaped once then xml escaped`() {
        val chapter = unzip(
            exportToBytes(chapters = listOf("Tom &amp; Jerry" to "A &amp;lt; B &amp; C"))
        ).getValue("OEBPS/chapter-001.xhtml")

        assertTrue(paragraphContent(chapter).contains("&amp;lt;"))
        assertFalse(paragraphContent(chapter).contains("&amp;amp;"))
        assertTrue(chapter.contains("<title>Tom &amp; Jerry</title>"))
        assertTrue(chapter.contains("<h2>Tom &amp; Jerry</h2>"))
    }

    @Test
    fun `image bytes are embedded and manifest updated`() {
        val entries = unzipRaw(
            exportStreamingToBytes(
                totalChapters = 1,
                chapterLoader = { _, _ ->
                    listOf("WithImg" to "Before <img src=\"fig.png\" alt=\"fig\"> After")
                },
                imageByteLoader = { if (it == "fig.png") jpeg else null }
            )
        )

        assertTrue(entries.containsKey("OEBPS/images/chapter-001-img-0.jpg"))
        assertEquals(jpeg.toList(), entries.getValue("OEBPS/images/chapter-001-img-0.jpg").toList())
        assertFalse(entries.containsKey("OEBPS/images/fig.png"))

        val chapter = entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        assertTrue(chapter.contains("""<img src="images/chapter-001-img-0.jpg" alt=""/>"""))
        assertFalse(chapter.contains("fig.png"))

        val opf = entries.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        assertTrue(opf.contains("""<item id="img-0" href="images/chapter-001-img-0.jpg" media-type="image/jpeg"/>"""))
    }

    @Test
    fun `image without bytes keeps original src and no manifest entry`() {
        val entries = unzipRaw(
            exportStreamingToBytes(
                totalChapters = 1,
                chapterLoader = { _, _ -> listOf("NoBytes" to "Text <img src=\"remote.png\">") },
                imageByteLoader = { null }
            )
        )

        assertFalse(entries.keys.any { it.startsWith("OEBPS/images/") })
        val chapter = entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        assertTrue(chapter.contains("""<img src="remote.png" alt=""/>"""))
        val opf = entries.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        assertFalse(opf.contains("""<item id="img-"""))
    }

    @Test
    fun `src html entities are unescaped once then xml escaped`() {
        val loaderSeen = mutableListOf<String>()
        val entries = unzipRaw(
            exportStreamingToBytes(
                totalChapters = 1,
                chapterLoader = { _, _ ->
                    listOf("Ent" to "Text <img src=\"https://img.site/a.png?id=1&amp;token=abc\">")
                },
                imageByteLoader = { src ->
                    loaderSeen += src
                    null
                }
            )
        )
        val chapter = entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)

        assertEquals(listOf("https://img.site/a.png?id=1&token=abc"), loaderSeen)
        assertTrue(chapter.contains("""<img src="https://img.site/a.png?id=1&amp;token=abc" alt=""/>"""))
        assertFalse(chapter.contains("&amp;amp;"))
    }

    @Test
    fun `image only chapter is kept with text order preserved`() {
        val entries = unzipRaw(
            exportStreamingToBytes(
                totalChapters = 2,
                chapterLoader = { _, _ ->
                    listOf(
                        "Text" to "First\n\nSecond",
                        "Manga" to "<img src=\"page1.png\"><img src=\"page2.jpeg\">",
                    )
                },
                imageByteLoader = { if (it.endsWith(".png")) png else jpeg }
            )
        )

        assertTrue(entries.containsKey("OEBPS/chapter-001.xhtml"))
        assertTrue(entries.containsKey("OEBPS/chapter-002.xhtml"))

        val chapter1 = entries.getValue("OEBPS/chapter-001.xhtml").toString(Charsets.UTF_8)
        assertEquals(listOf("First", "Second"), Regex("<p>(.*?)</p>").findAll(chapter1).map { it.groupValues[1] }.toList())

        assertTrue(entries.containsKey("OEBPS/images/chapter-002-img-0.png"))
        assertTrue(entries.containsKey("OEBPS/images/chapter-002-img-1.jpg"))
        val chapter2 = entries.getValue("OEBPS/chapter-002.xhtml").toString(Charsets.UTF_8)
        assertTrue(chapter2.contains("""<img src="images/chapter-002-img-0.png" alt=""/>"""))
        assertTrue(chapter2.contains("""<img src="images/chapter-002-img-1.jpg" alt=""/>"""))

        val opf = entries.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        assertTrue(opf.contains("""<item id="img-0""""))
        assertTrue(opf.contains("""<item id="img-1""""))
    }

    @Test
    fun `empty chapters are skipped and progress counts written chapters`() {
        val progress = mutableListOf<Pair<Int, Int>>()
        val entries = unzip(
            exportToBytes(
                chapters = listOf(
                    "First" to "Some text",
                    "Empty" to "   ",
                    "Broken" to "<img alt=\"x\">",
                ),
                onProgress = { done, total -> progress += done to total }
            )
        )

        assertTrue(entries.containsKey("OEBPS/chapter-001.xhtml"))
        assertFalse(entries.containsKey("OEBPS/chapter-002.xhtml"))
        assertFalse(entries.containsKey("OEBPS/chapter-003.xhtml"))
        assertEquals(1, Regex("<itemref idref=").findAll(entries.getValue("OEBPS/content.opf")).count())
        assertEquals(listOf(1 to 3), progress)
    }

    @Test
    fun `empty chapter list throws`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { export(ByteArrayOutputStream(), "t", "en", emptyList()) }
        }
    }

    @Test
    fun `jpeg cover is declared with cover image properties and meta`() {
        val entries = unzipRaw(exportToBytes(chapters = listOf("A" to "text"), coverBytes = jpeg))

        assertTrue(entries.containsKey("OEBPS/images/cover.jpg"))
        assertEquals(jpeg.toList(), entries.getValue("OEBPS/images/cover.jpg").toList())
        assertFalse(entries.containsKey("OEBPS/cover.xhtml"))

        val opf = entries.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        assertTrue(opf.contains("""<item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>"""))
        assertTrue(opf.contains("""<meta name="cover" content="cover-image"/>"""))
    }

    @Test
    fun `png cover gets png extension and mime`() {
        val entries = unzip(exportToBytes(chapters = listOf("A" to "text"), coverBytes = png))

        assertTrue(entries.containsKey("OEBPS/images/cover.png"))
        assertTrue(entries.getValue("OEBPS/content.opf").contains("""media-type="image/png""""))
    }

    @Test
    fun `description is added to metadata and escaped`() {
        val opf = unzip(
            exportToBytes(
                chapters = listOf("A" to "text"),
                description = "Great <book> & \"wow\"",
            )
        ).getValue("OEBPS/content.opf")

        assertTrue(opf.contains("<dc:description>Great &lt;book&gt; &amp; &quot;wow&quot;</dc:description>"))
    }

    @Test
    fun `opf includes creator and deduplicated subjects`() {
        val opf = unzip(
            exportToBytes(
                chapters = listOf("A" to "text"),
                creator = "A. Author & Co",
                subjects = listOf("Fantasy", "Fantasy", "Action"),
            )
        ).getValue("OEBPS/content.opf")

        assertTrue(opf.contains("<dc:creator>A. Author &amp; Co</dc:creator>"))
        assertEquals(1, Regex("<dc:subject>Fantasy</dc:subject>").findAll(opf).count())
        assertTrue(opf.contains("<dc:subject>Action</dc:subject>"))
    }

    @Test
    fun `streaming export uses batches in order and writes navigation`() {
        val batchCalls = mutableListOf<Pair<Int, Int>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val total = 250
        val entries = unzip(
            exportStreamingToBytes(
                totalChapters = total,
                chapterLoader = { offset, count ->
                    batchCalls += offset to count
                    (offset until minOf(offset + count, total)).map { i ->
                        "Chapter ${i + 1}" to "Body of chapter ${i + 1}"
                    }
                },
                onProgress = { done, all -> progress += done to all }
            )
        )

        assertEquals(listOf(0 to 200, 200 to 50), batchCalls)
        assertEquals(250, entries.keys.count { it.startsWith("OEBPS/chapter-") })
        assertTrue(entries.containsKey("OEBPS/chapter-250.xhtml"))
        assertEquals(250, Regex("<itemref idref=").findAll(entries.getValue("OEBPS/content.opf")).count())
        assertEquals(250, Regex("<li>").findAll(entries.getValue("OEBPS/nav.xhtml")).count())
        assertEquals(250, Regex("<navPoint ").findAll(entries.getValue("OEBPS/toc.ncx")).count())
        assertEquals(250, progress.size)
        assertEquals(250 to 250, progress.last())
    }
}
