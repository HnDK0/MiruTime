package dev.mirutime.app.features.settings.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelaBackupStagerTest {
    @Test
    fun `blank body becomes empty html`() {
        assertEquals("", novelaBodyToHtml(""))
        assertEquals("", novelaBodyToHtml("   \n\n  \t\n"))
    }

    @Test
    fun `blank lines separate paragraphs`() {
        assertEquals(
            "<p>One</p>\n<p>Two</p>\n<p>Three</p>",
            novelaBodyToHtml("One\n\nTwo\n\nThree"),
        )
    }

    @Test
    fun `single newlines fall back to paragraph breaks`() {
        assertEquals("<p>One</p>\n<p>Two</p>", novelaBodyToHtml("One\nTwo"))
    }

    @Test
    fun `spaces and nbsp collapse`() {
        assertEquals(
            "<p>a b c</p>",
            novelaBodyToHtml("a \t \u00A0  b   c"),
        )
    }

    @Test
    fun `angle brackets are escaped`() {
        assertEquals("<p>a ⟨b⟩ c</p>", novelaBodyToHtml("a <b> c"))
    }

    @Test
    fun `paragraph count matches input`() {
        val input = (1..7).joinToString("\n\n") { "p$it" }
        assertEquals(7, novelaBodyToHtml(input).split("</p>").size - 1)
    }

    @Test
    fun `block count is zero for an empty body`() {
        assertEquals(0, novelaBlockCount(""))
        assertEquals(0, novelaBlockCount("  \n\n \t"))
    }

    @Test
    fun `block count follows paragraph breaks`() {
        assertEquals(2, novelaBlockCount("One\n\nTwo"))
    }

    @Test
    fun `block count falls back to single newlines`() {
        assertEquals(2, novelaBlockCount("One\nTwo"))
    }

    @Test
    fun `short url maps to base64 folder`() {
        val url = "https://example.test/book/one"
        assertEquals(
            Base64.getEncoder().encodeToString(url.encodeToByteArray()),
            novelaCoverFolderName(url),
        )
    }

    @Test
    fun `short url has a single cover candidate`() {
        val url = "https://example.test/book/one"
        assertEquals(
            listOf(Base64.getEncoder().encodeToString(url.encodeToByteArray())),
            novelaCoverFolderCandidates(url),
        )
    }

    @Test
    fun `long url falls back to the legacy base64 cover folder`() {
        // 160 chars of URL encode to 216 Base64 chars, past the 200 cap.
        val url = "https://example.test/" + "a".repeat(140)
        val legacy = Base64.getEncoder().encodeToString(url.encodeToByteArray())
        assertTrue(legacy.length > 200)
        val candidates = novelaCoverFolderCandidates(url)
        assertEquals(2, candidates.size)
        assertTrue(candidates[0].startsWith("h_"))
        assertEquals(legacy, candidates[1])
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `long url maps to hashed folder`() {
        val url = "https://example.test/" + "a".repeat(220)
        val name = novelaCoverFolderName(url)
        assertTrue(name.startsWith("h_"))
        assertEquals(34, name.length)
        assertTrue(name.substring(2).all { it in "0123456789abcdef" })
    }

    @Test
    fun `base64 length over the cap maps to hashed folder`() {
        // 160 chars of URL encode to 216 Base64 chars: NoveLA hashes on the
        // encoded name, not on the URL length.
        val url = "https://example.test/" + "a".repeat(140)
        assertTrue(url.length <= 200)
        assertTrue(novelaCoverFolderName(url).startsWith("h_"))
    }

    @Test
    fun `cover folder may be a nested relative path`() {
        assertEquals("a", coverSegment("books/a/__cover_image"))
        assertEquals("ab/cd", coverSegment("books/ab/cd/__cover_image"))
        assertNull(coverSegment("books/../x/__cover_image"))
        assertNull(coverSegment("books/a/../__cover_image"))
        assertNull(coverSegment("books//__cover_image"))
        assertNull(coverSegment("settings.json"))
    }

    @Test
    fun `peek returns the first entry and leaves the zip readable`() {
        val bytes = zipOf(
            "database.sqlite3" to "db".encodeToByteArray(),
            "books/x/__cover_image" to "cover".encodeToByteArray(),
        )
        val (name, stream) = peekZipFirstEntry(ByteArrayInputStream(bytes))
        assertEquals("database.sqlite3", name)
        ZipInputStream(stream).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.readBytes()
            }
            assertEquals(listOf("database.sqlite3", "books/x/__cover_image"), names)
        }
    }

    @Test
    fun `peek passes non-zip bytes through`() {
        val bytes = "not a zip at all".encodeToByteArray()
        val (name, stream) = peekZipFirstEntry(ByteArrayInputStream(bytes))
        assertNull(name)
        assertTrue(bytes.contentEquals(stream.readBytes()))
    }

    @Test
    fun `peek passes a one byte stream through`() {
        val bytes = byteArrayOf(0x50)
        val (name, stream) = peekZipFirstEntry(ByteArrayInputStream(bytes))
        assertNull(name)
        assertTrue(bytes.contentEquals(stream.readBytes()))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
