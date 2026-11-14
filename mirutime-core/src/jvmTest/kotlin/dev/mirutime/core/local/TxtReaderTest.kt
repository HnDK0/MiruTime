package dev.mirutime.core.local

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TxtReaderTest {

    private fun txtFile(name: String, text: String, charset: Charset = Charsets.UTF_8): File {
        val dir = Files.createTempDirectory("mirutime-txt").toFile()
        return File(dir, name).apply {
            writeBytes(text.toByteArray(charset))
            deleteOnExit()
        }
    }

    private fun read(text: String, charset: Charset = Charsets.UTF_8): List<LocalChapter> {
        val chapters = mutableListOf<LocalChapter>()
        TxtFileReader(txtFile("book.txt", text, charset)).forEachChapter { chapters += it }
        return chapters
    }

    @Test
    fun `long book splits into chapters between paragraphs`() {
        val paragraphs = (1..500).map { index ->
            val prefix = "PARA$index "
            prefix + "x".repeat(200 - prefix.length)
        }

        val chapters = read(paragraphs.joinToString("\n"))

        assertEquals(2, chapters.size)
        assertEquals(paragraphs, chapters.flatMap { it.body.split("\n") })
        assertTrue(chapters.all { it.body.length <= TXT_CHAPTER_MAX_CHARS })
        assertEquals(listOf("Part 1", "Part 2"), chapters.map { it.title })
        assertEquals(listOf("txt_1", "txt_2"), chapters.map { it.url })
    }

    @Test
    fun `hard wrapped lines of a block are merged into one paragraph`() {
        val blocks = (0 until 3).map { block ->
            (0 until 3).joinToString(" ") { line -> "Block $block line $line " + "y".repeat(60) }
        }
        val chapters = read(blocks.joinToString("\n\n") + "\n")

        val body = chapters.single().body
        assertEquals(blocks, body.split("\n"))
        assertFalse(body.contains("\n\n"))
    }

    @Test
    fun `file without blank lines keeps every line as its own paragraph`() {
        val lines = (1..10).map { "Line $it with some words" }

        val chapters = read(lines.joinToString("\n"))

        assertEquals(lines, chapters.single().body.split("\n"))
    }

    @Test
    fun `cjk lines are merged without a space`() {
        val chapters = read(
            "日本語のテキストです\nこれはテストです\n\n二行目の段落です\n続きの文です\n",
        )

        val body = chapters.single().body
        assertEquals(
            "日本語のテキストですこれはテストです\n二行目の段落です続きの文です",
            body,
        )
        assertFalse(body.contains("\n\n"))
    }

    @Test
    fun `hyphenated line break is joined without the hyphen`() {
        val chapters = read("This is a broken hy-\nphenated word.\n")
        // No blank line anywhere, so the two lines stay separate paragraphs.
        assertEquals(listOf("This is a broken hy-", "phenated word."), chapters.single().body.split("\n"))

        val merged = read("This is a broken hy-\nphenated word.\n\nNext block.\n")
        assertEquals("This is a broken hyphenated word.", merged.single().body.split("\n").first())
    }

    @Test
    fun `oversized paragraph is split at the last space`() {
        val long = ("z".repeat(99) + " ").repeat(1000) + "z".repeat(50)

        val chapters = read(long)

        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.body.length <= TXT_CHAPTER_MAX_CHARS })
        assertEquals(long, chapters.joinToString(" ") { it.body })
    }

    @Test
    fun `oversized paragraph without spaces is split between code points`() {
        val long = "a" + "\uD83D\uDE00".repeat(40_000)

        val chapters = read(long)

        assertEquals(2, chapters.size)
        assertEquals(long, chapters.joinToString("") { it.body })
        chapters.forEach { chapter ->
            assertFalse(chapter.body.last().isHighSurrogate())
            assertFalse(chapter.body.first().isLowSurrogate())
        }
    }

    @Test
    fun `empty txt throws`() {
        val error = assertFailsWith<IllegalStateException> {
            TxtFileReader(txtFile("empty.txt", "")).forEachChapter {}
        }
        assertEquals("TXT has no text", error.message)
    }

    @Test
    fun `whitespace only txt throws`() {
        assertFailsWith<IllegalStateException> {
            TxtFileReader(txtFile("blank.txt", "\n \n\t\n")).forEachChapter {}
        }
    }

    @Test
    fun `title comes from the file name`() {
        val reader = TxtFileReader(txtFile("My Book.txt", "Some text.\n"))
        assertEquals("My Book", reader.title)
    }

    @Test
    fun `cp1251 file is decoded without replacement characters`() {
        val text = "Он медленно шёл по узкой улице. Над ним висели тяжёлые облака."

        val chapters = read(text, Charset.forName("windows-1251"))

        val body = chapters.single().body
        assertEquals(text, body)
        assertFalse(body.contains('\uFFFD'))
    }

    @Test
    fun `paragraph of exactly the chapter limit stays in one chapter`() {
        val chapters = read("x".repeat(TXT_CHAPTER_MAX_CHARS))

        assertEquals(listOf(TXT_CHAPTER_MAX_CHARS), chapters.map { it.body.length })
    }

    @Test
    fun `paragraph one char over the chapter limit is cut`() {
        val chapters = read("x".repeat(TXT_CHAPTER_MAX_CHARS + 1))

        assertEquals(listOf(TXT_CHAPTER_MAX_CHARS, 1), chapters.map { it.body.length })
    }

    @Test
    fun `cr only line breaks are line breaks`() {
        val chapters = read("Line one\rLine two\rLine three")

        assertEquals(
            listOf("Line one", "Line two", "Line three"),
            chapters.single().body.split("\n"),
        )
    }

    @Test
    fun `megabyte without line breaks is read without buffering it whole`() {
        val chapters = read("a".repeat(1_000_000))

        assertEquals(1_000_000, chapters.sumOf { chapter -> chapter.body.count { it != '\n' } })
        assertTrue(chapters.all { it.body.length <= TXT_CHAPTER_MAX_CHARS })
    }

    @Test
    fun `giant merged paragraph is capped and its text is kept`() {
        val lines = (1..300).map { index -> "Line $index " + "q".repeat(990) }
        val chapters = read(lines.joinToString("\n") + "\n\n")

        val paragraphs = chapters.flatMap { it.body.split("\n") }
        assertTrue(paragraphs.size >= 2)
        assertTrue(paragraphs.all { it.length <= TXT_PARAGRAPH_MAX_CHARS })
        assertEquals(lines.joinToString(" "), paragraphs.joinToString(" "))
    }

    @Test
    fun `surrogate pair at the chunk boundary stays whole`() {
        for (offset in listOf(255_999, 256_000)) {
            // "" keeps the file without blank lines (mergeLines = false), the
            // "\n\n" suffix turns merging on for the same long line.
            for (suffix in listOf("", "\n\n")) {
                val text = "a".repeat(offset) + "\uD83D\uDE00" + "b".repeat(10_000) + suffix
                val chapters = read(text)

                // Bodies carry one \n per paragraph boundary; the fixture has no
                // source line breaks inside the long line.
                assertEquals(
                    text.replace("\n", ""),
                    chapters.joinToString("") { it.body.replace("\n", "") },
                )
                chapters.forEach { chapter ->
                    assertFalse(chapter.body.last().isHighSurrogate())
                    assertFalse(chapter.body.first().isLowSurrogate())
                }
            }
        }
    }
}
