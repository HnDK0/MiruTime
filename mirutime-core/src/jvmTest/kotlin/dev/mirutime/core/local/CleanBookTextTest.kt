package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun container(opfPath: String) = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

private fun opf(manifest: String, spine: String) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
  <manifest>$manifest</manifest>
  <spine>$spine</spine>
</package>"""

private fun page(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body>$body</body></html>"""

private fun item(id: String, href: String, properties: String = "", mediaType: String = "application/xhtml+xml"): String {
    val props = if (properties.isEmpty()) "" else " properties=\"$properties\""
    return "<item id=\"$id\" href=\"$href\" media-type=\"$mediaType\"$props/>"
}

private fun epub(opfPath: String, manifest: String, spine: String, vararg files: Pair<String, String>): ByteArray =
    zipOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container(opfPath),
        opfPath to opf(manifest, spine),
        *files,
    )

// The in-memory parser (FileSystem fallback) and the ZipFile-backed one (production) duplicate the
// logic, so every case runs against both.
private fun readBoth(bytes: ByteArray): List<LocalBook> {
    val file = File.createTempFile("mirutime-test", ".epub")
    try {
        file.writeBytes(bytes)
        return listOf(parseEpub(bytes), parseLocalBookFile(file, "book.epub"))
    } finally {
        file.delete()
    }
}

private const val ONE_CHAPTER = """<itemref idref="c1"/>"""

private fun fb2(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>T</book-title></title-info></description>
<body>$body</body></FictionBook>"""

private fun epubBodies(body: String): List<String> =
    readBoth(epub("OEBPS/content.opf", item("c1", "ch1.xhtml"), ONE_CHAPTER, "OEBPS/ch1.xhtml" to page(body)))
        .map { it.chapters.single().body.trim() }

class CleanBookTextTest {
    @Test
    fun softHyphensWordJoinersAndBomAreRemoved() {
        assertEquals("international", "inter\u00ADnational".cleanBookText())
        assertEquals("agreement", "agree\u2060ment".cleanBookText())
        assertEquals("text", "\uFEFFtext".cleanBookText())
    }

    @Test
    fun aNoBreakSpaceBecomesASpace() {
        assertEquals("10 km", "10\u00A0km".cleanBookText())
    }

    @Test
    fun zeroWidthSpacesAndJoinersAreLeftAlone() {
        assertEquals("a\u200Bb\u200Cc\u200Dd", "a\u200Bb\u200Cc\u200Dd".cleanBookText())
    }

    @Test
    fun plainTextIsReturnedAsIs() {
        val text = "Nothing to clean here."
        assertSame(text, text.cleanBookText())
    }

    @Test
    fun fb2TextIsCleaned() {
        val book = parseFb2(fb2("<section><p>inter\u00ADnational\u00A0agree\u2060ment.</p></section>").toByteArray())
        assertEquals("international agreement.", book.chapters.single().body.trim())
    }

    // Separate cases, because the HTML parser may already handle some of these on its own: a run
    // before the change shows which ones the change is actually needed for.
    @Test
    fun epubWordJoinerIsRemoved() {
        for (body in epubBodies("<p>agree\u2060ment.</p>")) assertEquals("agreement.", body)
    }

    @Test
    fun epubSoftHyphenIsRemoved() {
        for (body in epubBodies("<p>inter\u00ADnational.</p>")) assertEquals("international.", body)
    }

    @Test
    fun epubNoBreakSpaceBecomesASpace() {
        for (body in epubBodies("<p>10\u00A0km.</p>")) assertEquals("10 km.", body)
    }

    // An inline image is placed by character offset: removing characters before it must not shift it.
    @Test
    fun cleaningKeepsImageOffsets() {
        val builder = InlineImagesBuilder()
        builder.append("inter\u00AD")
        builder.image(LocalImage(position = 0, bytes = byteArrayOf(1)))
        builder.append("national\u00A0text")
        val parsed = builder.build()

        assertEquals("international text", parsed.body)
        assertEquals(5, parsed.images.single().position)
    }
}
