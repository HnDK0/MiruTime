package dev.mirutime.core.local

import dev.mirutime.core.host.FakeFileSystem
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileBackedLocalBookTest {

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tempZip(bytes: ByteArray): File =
        Files.createTempFile("mirutime-", ".zip").toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun epub(chapterCount: Int): ByteArray {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val chapters = mutableListOf<Pair<String, ByteArray>>()
        for (i in 1..chapterCount) {
            manifest.append("""<item id="ch$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="ch$i"/>""")
            chapters += "OEBPS/ch$i.xhtml" to
                "<html><body><p>Chapter $i body.</p></body></html>".encodeToByteArray()
        }
        val opf = """
            <package><metadata><dc:title>Long Book</dc:title></metadata>
            <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>$manifest</manifest>
            <spine><itemref idref="ncx"/>$spine</spine></package>
        """.trimIndent().encodeToByteArray()
        val ncx = StringBuilder("<ncx><navMap>")
        for (i in 1..chapterCount) {
            ncx.append("<navPoint><navLabel><text>Chapter $i</text></navLabel><content src=\"ch$i.xhtml\"/></navPoint>")
        }
        ncx.append("</navMap></ncx>")
        return zip(
            listOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
                        .encodeToByteArray(),
                "OEBPS/content.opf" to opf,
                "OEBPS/toc.ncx" to ncx.toString().encodeToByteArray(),
            ) + chapters,
        )
    }

    @Test
    fun `file-backed epub yields chapters one by one`() {
        val file = tempZip(epub(300))
        var count = 0
        EpubFileReader(file).use { reader ->
            assertEquals(300, reader.spineCount)
            reader.forEachChapter { chapter ->
                count++
                assertEquals("Chapter $count", chapter.title)
                assertEquals("Chapter $count body.", chapter.body.trim())
            }
        }
        assertEquals(300, count)
    }

    @Test
    fun `oversized zip entry is rejected and unsafe names are ignored`() {
        val file = tempZip(
            zip(
                listOf(
                    "big.bin" to ByteArray(33 * 1024 * 1024),
                    "../evil.bin" to byteArrayOf(1),
                ),
            ),
        )
        ZipArchive(file).use { archive ->
            assertFailsWith<IllegalStateException> { archive.read("big.bin") }
            assertNull(archive.read("../evil.bin"))
            assertNull(archive.read("missing.bin"))
        }
    }

    @Test
    fun `cbz page read materializes only the requested entry`() {
        val file = tempZip(
            zip(
                listOf(
                    "big.png" to ByteArray(40 * 1024 * 1024),
                    "cover.png" to byteArrayOf(1, 2, 3),
                    "page-2.png" to byteArrayOf(4, 5),
                ),
            ),
        )
        CbzFileReader(file).use { reader ->
            assertContentEquals(byteArrayOf(1, 2, 3), reader.coverBytes)
            assertEquals(3, reader.imageNames.size)
            assertContentEquals(byteArrayOf(4, 5), reader.readPage("page-2.png"))
            assertFailsWith<IllegalStateException> { reader.readPage("big.png") }
        }
    }

    @Test
    fun `LocalSource pageBytes uses the file resolver, not a whole-file read`() {
        val file = tempZip(
            zip(
                listOf(
                    "cover.png" to byteArrayOf(7, 8),
                    "page.png" to byteArrayOf(9),
                ),
            ),
        )
        val source = LocalSource(
            fileSystem = FakeFileSystem(),
            libraryDir = "/library",
            fileResolver = { file },
        )
        val bytes = kotlinx.coroutines.runBlocking {
            source.pageBytes("ignored.cbz#cover.png")
        }
        assertContentEquals(byteArrayOf(7, 8), bytes)
    }
}
