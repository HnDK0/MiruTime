package dev.mirutime.core.local

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader

// Streaming TXT reader: no whole-file buffer and no size cap. Memory is bounded
// by one paragraph (256k chars) plus one chapter (64k chars); the encoding is
// detected from the first 64 KB.
class TxtFileReader(private val file: File) {
    val title: String = file.nameWithoutExtension.ifEmpty { "Untitled" }

    fun forEachChapter(consumer: (LocalChapter) -> Unit) {
        val charset = detectTxtCharset(readSample(file)).charset
        readTxtChapters({ InputStreamReader(FileInputStream(file), charset) }, consumer)
    }

    fun readAll(): LocalBook {
        val chapters = mutableListOf<LocalChapter>()
        forEachChapter { chapters += it }
        return LocalBook(title = title, chapters = chapters)
    }
}

internal fun parseTxt(bytes: ByteArray, title: String = "Untitled"): LocalBook {
    val sample = bytes.copyOf(minOf(bytes.size, TXT_SAMPLE_BYTES))
    val charset = detectTxtCharset(sample).charset
    val chapters = mutableListOf<LocalChapter>()
    readTxtChapters({ InputStreamReader(ByteArrayInputStream(bytes), charset) }) { chapters += it }
    return LocalBook(title = title, chapters = chapters)
}

internal const val TXT_CHAPTER_MAX_CHARS = 64_000
internal const val TXT_PARAGRAPH_MAX_CHARS = 256_000
private const val TXT_SAMPLE_BYTES = 64 * 1024

private fun readSample(file: File): ByteArray {
    FileInputStream(file).use { input ->
        val buffer = ByteArray(TXT_SAMPLE_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val n = input.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }
}

// Two passes: the merge rule depends on whether the file has any blank line at
// all, so the first pass only answers that question. `open` re-creates the
// decoded reader for the actual parse.
internal fun readTxtChapters(open: () -> Reader, consumer: (LocalChapter) -> Unit) {
    val mergeLines = open().use { hasBlankLine(it) }
    val sink = TxtChapterSink(consumer)
    var paragraph = StringBuilder()
    var fileStart = true

    fun flushParagraph() {
        sink.add(paragraph.toString())
        paragraph = StringBuilder()
    }

    // A merged paragraph stops at the cap instead of growing to the whole file;
    // the continuation starts a new paragraph, no character is dropped.
    fun appendText(text: String, joined: Boolean) {
        if (text.isEmpty()) return
        val extra = if (joined && paragraph.isNotEmpty()) 1 else 0
        if (paragraph.isNotEmpty() && paragraph.length + text.length + extra > TXT_PARAGRAPH_MAX_CHARS) {
            flushParagraph()
        }
        if (joined && paragraph.isNotEmpty()) appendJoined(paragraph, text) else paragraph.append(text)
    }

    open().use { input ->
        val reader = TxtLineReader(input)
        while (true) {
            val chunk = reader.nextChunk() ?: break
            var text = chunk.text
            if (fileStart) {
                text = text.removePrefix("\uFEFF")
                fileStart = false
            }
            if (text.contains('\u001A')) text = text.replace("\u001A", "")
            if (chunk.isLineEnd) text = text.trimEnd()
            if (chunk.isLineStart && chunk.isLineEnd && text.isBlank()) {
                flushParagraph()
                continue
            }
            if (!mergeLines) {
                if (chunk.isLineStart) flushParagraph()
                appendText(text, joined = false)
                if (chunk.isLineEnd) flushParagraph()
            } else {
                val startsLine = chunk.isLineStart && paragraph.isNotEmpty()
                if (startsLine && startsNewParagraph(text)) {
                    flushParagraph()
                    appendText(text, joined = false)
                } else {
                    appendText(text, joined = startsLine)
                }
            }
        }
    }
    flushParagraph()
    sink.flush()
    if (sink.chapterCount == 0) throw IllegalStateException("TXT has no text")
}

private fun hasBlankLine(input: Reader): Boolean {
    val reader = TxtLineReader(input)
    while (true) {
        val chunk = reader.nextChunk() ?: return false
        if (chunk.isLineStart && chunk.isLineEnd && chunk.text.isBlank()) return true
    }
}

private data class TxtLineChunk(val text: String, val isLineStart: Boolean, val isLineEnd: Boolean)

// readLine() would materialize a whole line, so a line longer than a paragraph
// is returned as chunks: the continuation is part of the same line and must not
// start a new paragraph or re-apply the break markers.
private class TxtLineReader(input: Reader) {
    private val reader = if (input is BufferedReader) input else BufferedReader(input)
    private val buffer = CharArray(8192)
    private var index = 0
    private var size = 0
    private var pushback = -1
    private var atLineStart = true
    private var eof = false

    fun nextChunk(): TxtLineChunk? {
        if (eof) return null
        val chunk = StringBuilder()
        while (true) {
            val c = readChar()
            if (c < 0) {
                eof = true
                if (chunk.isEmpty()) return null
                return TxtLineChunk(chunk.toString(), atLineStart, true).also { atLineStart = true }
            }
            if (c == '\n'.code || c == '\r'.code) {
                if (c == '\r'.code) {
                    val next = readChar()
                    if (next >= 0 && next != '\n'.code) pushback = next
                }
                return TxtLineChunk(chunk.toString(), atLineStart, true).also { atLineStart = true }
            }
            chunk.append(c.toChar())
            if (chunk.length >= TXT_PARAGRAPH_MAX_CHARS) {
                val start = atLineStart
                atLineStart = false
                // A high surrogate at the cut must move to the next chunk, or the
                // pair splits between two paragraphs (and chapters).
                val text = if (chunk[chunk.length - 1].isHighSurrogate()) {
                    pushback = chunk[chunk.length - 1].code
                    chunk.substring(0, chunk.length - 1)
                } else {
                    chunk.toString()
                }
                return TxtLineChunk(text, start, false)
            }
        }
    }

    private fun readChar(): Int {
        if (pushback >= 0) {
            val c = pushback
            pushback = -1
            return c
        }
        if (index >= size) {
            size = reader.read(buffer)
            index = 0
            if (size <= 0) return -1
        }
        return buffer[index++].code
    }
}

private val ParagraphStart = Regex("^(?:[—–\\-«\"#*|>]|\\d+[.)])")

private fun startsNewParagraph(line: String): Boolean = ParagraphStart.containsMatchIn(line)

private fun appendJoined(paragraph: StringBuilder, text: String) {
    if (text.isEmpty()) return
    val last = paragraph[paragraph.length - 1]
    val first = text[0]
    when {
        isCjkOrThai(last) && isCjkOrThai(first) -> Unit
        last == '-' && first.isLowerCase() -> paragraph.setLength(paragraph.length - 1)
        else -> paragraph.append(' ')
    }
    paragraph.append(text)
}

private fun isCjkOrThai(c: Char): Boolean =
    isHanChar(c) || isKanaChar(c) || isHangulChar(c) || c in '\u0E00'..'\u0E7F'

private class TxtChapterSink(private val consumer: (LocalChapter) -> Unit) {
    private val paragraphs = mutableListOf<String>()
    private var length = 0
    var chapterCount = 0
        private set

    fun add(paragraph: String) {
        if (paragraph.isEmpty()) return
        if (paragraph.length > TXT_CHAPTER_MAX_CHARS) {
            flush()
            splitOversized(paragraph).forEach { add(it) }
            return
        }
        if (length > 0 && length + 1 + paragraph.length > TXT_CHAPTER_MAX_CHARS) flush()
        if (length > 0) length++
        paragraphs += paragraph
        length += paragraph.length
    }

    fun flush() {
        if (paragraphs.isEmpty()) return
        chapterCount++
        consumer(
            LocalChapter(
                url = "txt_$chapterCount",
                title = "Part $chapterCount",
                body = paragraphs.joinToString("\n"),
                images = emptyList(),
            ),
        )
        paragraphs.clear()
        length = 0
    }
}

// Only a paragraph longer than one chapter is cut; the rest of the book never
// splits inside a paragraph.
private fun splitOversized(paragraph: String): List<String> {
    val parts = mutableListOf<String>()
    var rest = paragraph
    while (rest.length > TXT_CHAPTER_MAX_CHARS) {
        val space = rest.lastIndexOf(' ', TXT_CHAPTER_MAX_CHARS)
        val cut = if (space > 0) space else codePointBoundary(rest, TXT_CHAPTER_MAX_CHARS)
        val head = rest.substring(0, cut).trimEnd()
        if (head.isNotEmpty()) parts += head
        rest = rest.substring(if (space > 0) space + 1 else cut).trimStart()
    }
    if (rest.isNotEmpty()) parts += rest
    return parts
}

private fun codePointBoundary(text: String, limit: Int): Int =
    if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
