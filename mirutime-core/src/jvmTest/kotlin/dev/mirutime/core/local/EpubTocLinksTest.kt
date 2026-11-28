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

private fun nav(vararg links: Pair<String, String>): String {
    val items = links.joinToString("") { (href, title) -> """<li><a href="$href">$title</a></li>""" }
    return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head>
<body><nav epub:type="toc"><ol>$items</ol></nav></body></html>"""
}

private fun ncx(vararg points: Pair<String, String>): String {
    val items = points.withIndex().joinToString("") { (i, point) ->
        """<navPoint id="n$i" playOrder="${i + 1}"><navLabel><text>${point.second}</text></navLabel><content src="${point.first}"/></navPoint>"""
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>T</text></docTitle><navMap>$items</navMap></ncx>"""
}

private const val NCX_TYPE = "application/x-dtbncx+xml"

class EpubTocLinksTest {
    @Test
    fun navLinksAreRelativeToTheNavDocument() {
        val data = epub(
            "OEBPS/content.opf",
            item("nav", "Text/nav.xhtml", "nav") + item("c1", "Text/ch1.xhtml"),
            ONE_CHAPTER,
            "OEBPS/Text/nav.xhtml" to nav("ch1.xhtml" to "First"),
            "OEBPS/Text/ch1.xhtml" to page("<p>Body.</p>"),
        )
        for (book in readBoth(data)) {
            val chapter = book.chapters.single()
            assertEquals("First", chapter.title)
            assertTrue("Body." in chapter.body, chapter.body)
        }
    }

    @Test
    fun linksWrittenRelativeToTheOpfFolderStillWork() {
        val data = epub(
            "OEBPS/content.opf",
            item("nav", "Text/nav.xhtml", "nav") + item("c1", "Text/ch1.xhtml"),
            ONE_CHAPTER,
            "OEBPS/Text/nav.xhtml" to nav("Text/ch1.xhtml" to "Legacy"),
            "OEBPS/Text/ch1.xhtml" to page("<p>Body.</p>"),
        )
        for (book in readBoth(data)) {
            assertEquals("Legacy", book.chapters.single().title)
        }
    }

    @Test
    fun aTocThatReachesNoSpineFileFallsBackToOneChapterPerFile() {
        val data = epub(
            "OEBPS/content.opf",
            item("nav", "nav.xhtml", "nav") + item("c1", "ch1.xhtml"),
            ONE_CHAPTER,
            "OEBPS/nav.xhtml" to nav("missing.xhtml" to "Nowhere"),
            "OEBPS/ch1.xhtml" to page("<p>Still here.</p>"),
        )
        for (book in readBoth(data)) {
            val chapter = book.chapters.single()
            assertEquals("One", chapter.title)
            assertTrue("Still here." in chapter.body, chapter.body)
        }
    }

    @Test
    fun aStaleNcxFallsBackToTheNavDocument() {
        val data = epub(
            "OEBPS/content.opf",
            item("ncx", "toc.ncx", mediaType = NCX_TYPE) + item("nav", "nav.xhtml", "nav") + item("c1", "ch1.xhtml"),
            ONE_CHAPTER,
            "OEBPS/toc.ncx" to ncx("missing.xhtml" to "From ncx"),
            "OEBPS/nav.xhtml" to nav("ch1.xhtml" to "From nav"),
            "OEBPS/ch1.xhtml" to page("<p>Body.</p>"),
        )
        for (book in readBoth(data)) {
            assertEquals("From nav", book.chapters.single().title)
        }
    }

    @Test
    fun aWorkingNcxStillWinsOverTheNavDocument() {
        val data = epub(
            "OEBPS/content.opf",
            item("ncx", "toc.ncx", mediaType = NCX_TYPE) + item("nav", "nav.xhtml", "nav") + item("c1", "ch1.xhtml"),
            ONE_CHAPTER,
            "OEBPS/toc.ncx" to ncx("ch1.xhtml" to "From ncx"),
            "OEBPS/nav.xhtml" to nav("ch1.xhtml" to "From nav"),
            "OEBPS/ch1.xhtml" to page("<p>Body.</p>"),
        )
        for (book in readBoth(data)) {
            assertEquals("From ncx", book.chapters.single().title)
        }
    }

    @Test
    fun aTocThatOnlyReachesANonChapterSpineItemStillYieldsTheText() {
        // Converted books keep the cover in the spine; a TOC that links only it is not a usable TOC.
        val data = epub(
            "OEBPS/content.opf",
            item("nav", "nav.xhtml", "nav") + item("cover", "cover.jpg", mediaType = "image/jpeg") +
                item("c1", "ch1.xhtml"),
            """<itemref idref="cover"/><itemref idref="c1"/>""",
            "OEBPS/nav.xhtml" to nav("cover.jpg" to "Cover"),
            "OEBPS/ch1.xhtml" to page("<p>Body.</p>"),
            "OEBPS/cover.jpg" to "not a real jpeg",
        )
        for (book in readBoth(data)) {
            val chapter = book.chapters.single()
            assertTrue("Body." in chapter.body, chapter.body)
        }
    }
}
