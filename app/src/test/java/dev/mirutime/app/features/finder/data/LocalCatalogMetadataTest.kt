package dev.mirutime.app.features.finder.data

import dev.mirutime.core.local.ComicInfoMeta
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCatalogMetadataTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3,
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)

    @Test
    fun `cache key is stable and changes with identity mtime or size`() {
        val uri = "content://tree/book/document/one.epub"
        val key = catalogMetadataCacheKey(uri, 10, 100)

        assertEquals(key, catalogMetadataCacheKey(uri, 10, 100))
        assertNotEquals(key, catalogMetadataCacheKey(uri, 11, 100))
        assertNotEquals(key, catalogMetadataCacheKey(uri, 10, 101))
        assertNotEquals(key, catalogMetadataCacheKey("$uri-other", 10, 100))
        // Folder entries use the same URI + best-effort mtime/size contract.
        assertNotEquals(
            catalogMetadataCacheKey("content://tree/folder", 10, 100),
            catalogMetadataCacheKey("content://tree/folder", 10, 200),
        )
    }

    @Test
    fun `cover magic validates known image formats and rejects error pages`() {
        assertEquals("png", coverImageExtension(png))
        assertEquals("jpg", coverImageExtension(jpeg))
        assertEquals("gif", coverImageExtension("GIF89a".encodeToByteArray()))
        assertEquals(
            "webp",
            coverImageExtension("RIFFxxxxWEBP".encodeToByteArray()),
        )
        assertEquals("bmp", coverImageExtension("BM".encodeToByteArray()))
        assertNull(coverImageExtension("<html>not an image</html>".encodeToByteArray()))
        assertNull(coverImageExtension(ByteArray(0)))
    }

    private fun epubFile(): java.io.File {
        val file = temp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            )
            put(
                "OEBPS/content.opf",
                """
                <package><metadata>
                <dc:title>Embedded Book</dc:title>
                <dc:creator>Jane Doe</dc:creator>
                <dc:description>About it</dc:description>
                <meta name="cover" content="cover-image"/>
                </metadata>
                <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                </manifest>
                <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                </package>
                """.trimIndent(),
            )
            put(
                "OEBPS/toc.ncx",
                "<ncx><navMap><navPoint><navLabel><text>One</text></navLabel><content src=\"ch1.xhtml\"/></navPoint></navMap></ncx>",
            )
            put("OEBPS/ch1.xhtml", "<html><body><p>One</p></body></html>")
            put("OEBPS/ch2.xhtml", "<html><body><p>Two</p></body></html>")
            put("OEBPS/cover.jpg", jpeg)
        }
        return file
    }

    @Test
    fun `epub metadata reads title author description cover and spine count`() {
        val parsed = readEpubMetadata(epubFile())

        assertEquals("Embedded Book", parsed.metadata.title)
        assertEquals("Jane Doe", parsed.metadata.author)
        assertEquals("About it", parsed.metadata.description)
        assertEquals(2, parsed.metadata.chapterCount)
        assertArrayEquals(jpeg, parsed.coverBytes)
    }

    @Test
    fun `cbz metadata reads ComicInfo title and first page cover`() {
        val file = temp.newFile("comic.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "ComicInfo.xml",
                "<ComicInfo><Title>My Comic</Title><Summary>Summary</Summary></ComicInfo>"
                    .encodeToByteArray(),
            )
            put("002.png", png)
            put("001.png", png)
        }

        val parsed = readCbzMetadata(file)

        assertEquals("My Comic", parsed.metadata.title)
        assertEquals("Summary", parsed.metadata.description)
        assertEquals(2, parsed.metadata.chapterCount)
        assertArrayEquals(png, parsed.coverBytes)
    }

    @Test
    fun `folder metadata prefers ComicInfo title author and first page cover`() {
        val parsed = folderCatalogMetadata(
            comic = ComicInfoMeta(
                title = "Folder Comic",
                author = "Ann",
                summary = "Summary",
            ),
            chapterCount = 3,
            coverBytes = jpeg,
        )

        assertEquals("Folder Comic", parsed.metadata.title)
        assertEquals("Ann", parsed.metadata.author)
        assertEquals(3, parsed.metadata.chapterCount)
        assertArrayEquals(jpeg, parsed.coverBytes)
    }

    @Test
    fun `folder metadata omits title author and cover when ComicInfo is absent`() {
        val parsed = folderCatalogMetadata(
            comic = null,
            chapterCount = 1,
            coverBytes = null,
        )

        assertNull(parsed.metadata.title)
        assertNull(parsed.metadata.author)
        assertNull(parsed.coverBytes)
    }
}
