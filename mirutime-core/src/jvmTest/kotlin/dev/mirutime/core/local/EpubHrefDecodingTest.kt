package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

class EpubHrefDecodingTest {
    @Test
    fun plusSignsArePlusSignsAndEscapesAreDecoded() {
        assertEquals("images/a+b.jpg", "images/a+b.jpg".decodedURL)
        assertEquals("Text/chapter 1.xhtml", "Text/chapter%201.xhtml".decodedURL)
        assertEquals("Text/a+b c.xhtml", "Text/a+b%20c.xhtml".decodedURL)
    }

    @Test
    fun aStrayPercentSignIsLeftAlone() {
        assertEquals("100%.xhtml", "100%.xhtml".decodedURL)
    }

    @Test
    fun anHrefWithAPlusSignFindsItsFile() {
        val data = epub(
            "OEBPS/content.opf",
            item("c1", "a+b.xhtml"),
            ONE_CHAPTER,
            "OEBPS/a+b.xhtml" to page("<p>Plus sign.</p>"),
        )
        for (book in readBoth(data)) {
            assertTrue("Plus sign." in book.chapters.single().body, book.chapters.toString())
        }
    }
}
