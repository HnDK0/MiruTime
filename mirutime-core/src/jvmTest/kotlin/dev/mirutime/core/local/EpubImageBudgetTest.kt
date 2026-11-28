package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private fun zipOfBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

private const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

private const val OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
</package>"""

private fun chapter(n: Int) =
    text("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $n</title></head><body><p>Text $n.</p><img src="img/ornament.png"/></body></html>""")

class EpubImageBudgetTest {
    @Test
    fun anImageRepeatedInEveryChapterIsChargedAndHeldOnce() {
        val ornament = ByteArray(1000) { 7 }
        val data = zipOfBytes(
            listOf(
                "mimetype" to text("application/epub+zip"),
                "META-INF/container.xml" to text(CONTAINER),
                "OEBPS/content.opf" to text(OPF),
                "OEBPS/ch1.xhtml" to chapter(1),
                "OEBPS/ch2.xhtml" to chapter(2),
                "OEBPS/ch3.xhtml" to chapter(3),
                "OEBPS/img/ornament.png" to ornament,
            ),
        )
        val file = File.createTempFile("mirutime-test", ".epub")
        try {
            file.writeBytes(data)
            EpubFileReader(file).use { reader ->
                val chapters = reader.readAll().chapters
                assertEquals(3, chapters.size)
                val first = chapters[0].images.single().bytes
                for (chapter in chapters) {
                    assertSame(first, chapter.images.single().bytes)
                }
                assertEquals(MAX_ZIP_TOTAL_IMAGES_BYTES - ornament.size, reader.imageBudget)
            }
        } finally {
            file.delete()
        }
    }
}
