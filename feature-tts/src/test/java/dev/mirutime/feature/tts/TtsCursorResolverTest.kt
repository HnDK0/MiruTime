package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsCursorResolverTest {
    private val chapterUrl = "book#chapter=1"

    private fun chunk(
        block: Int,
        sentence: Int,
        start: Int,
        end: Int,
        translated: Boolean = false,
    ) = TtsChunk(BlockId(chapterUrl, block), sentence, "text", start, end, translated)

    private fun session(
        block: Int = 0,
        sentence: Int = 1,
        offset: Int = 16,
        chapter: String = chapterUrl,
        translated: Boolean = false,
    ) = TtsSession(
        bookUrl = "book",
        chapterIndex = 1,
        chapterUrl = chapter,
        blockIndex = block,
        sentenceIndex = sentence,
        charOffset = offset,
        translated = translated,
    )

    @Test
    fun `exact block sentence and offset wins`() {
        val chunks = listOf(chunk(0, 0, 0, 15), chunk(0, 1, 16, 31), chunk(1, 0, 32, 40))

        assertEquals(1, resolveTtsCursor(chunks, session()))
    }

    @Test
    fun `moved offset falls back to block and sentence`() {
        val chunks = listOf(chunk(0, 0, 0, 15), chunk(0, 1, 20, 35))

        assertEquals(1, resolveTtsCursor(chunks, session(offset = 16)))
    }

    @Test
    fun `re-chunked sentence falls back to nearest offset in the block`() {
        val chunks = listOf(
            chunk(0, 0, 0, 10),
            chunk(0, 0, 10, 20),
            chunk(0, 0, 20, 30),
        )

        assertEquals(2, resolveTtsCursor(chunks, session(sentence = 9, offset = 21)))
    }

    @Test
    fun `chapter mismatch is rejected`() {
        val chunks = listOf(chunk(0, 1, 16, 31))

        assertNull(resolveTtsCursor(chunks, session(chapter = "book#chapter=2")))
    }

    @Test
    fun `missing block is rejected`() {
        val chunks = listOf(chunk(0, 1, 16, 31))

        assertNull(resolveTtsCursor(chunks, session(block = 5)))
    }

    @Test
    fun `cursor on a skipped block moves to the next spoken block`() {
        val chunks = listOf(chunk(0, 0, 0, 10), chunk(3, 0, 40, 50))

        assertEquals(1, resolveTtsCursor(chunks, session(block = 2)))
    }

    @Test
    fun `empty chunks are rejected`() {
        assertNull(resolveTtsCursor(emptyList(), session()))
    }
}
