package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionCacheTest {
    private val session = TtsSession(
        bookUrl = "book",
        chapterIndex = 1,
        chapterUrl = "chapter",
        blockIndex = 2,
        sentenceIndex = 0,
        charOffset = 0,
    )

    @Test
    fun `load decodes once and caches`() {
        var reads = 0
        val cache = TtsSessionCache(
            read = { reads++; "raw" },
            write = { _, _, _ -> },
            encode = { "encoded" },
            decode = { if (it == "raw") session else null },
        )

        assertSame(session, cache.load())
        assertSame(session, cache.load())
        assertEquals(1, reads)
    }

    @Test
    fun `save is visible before the writer drains`() {
        val writes = mutableListOf<Pair<String?, Boolean>>()
        val cache = TtsSessionCache(
            read = { null },
            write = { value, immediate, _ -> writes += value to immediate },
            encode = { "encoded" },
            decode = { null },
        )

        cache.save(session, immediate = true)

        assertSame(session, cache.load())
        assertEquals("encoded", writes.single().first)
        assertTrue(writes.single().second)
    }

    @Test
    fun `invalidate re-reads the persisted session`() {
        var raw = "old"
        val cache = TtsSessionCache(
            read = { raw },
            write = { _, _, _ -> },
            encode = { "encoded" },
            decode = { if (it == "new") session.copy(chapterIndex = 9) else null },
        )

        assertNull(cache.load())
        raw = "new"
        cache.invalidate()

        assertEquals(9, cache.load()?.chapterIndex)
    }

    @Test
    fun `clear hides the session and writes null`() {
        val writes = mutableListOf<String?>()
        val cache = TtsSessionCache(
            read = { null },
            write = { value, _, _ -> writes += value },
            encode = { "encoded" },
            decode = { session },
        )

        cache.clear(immediate = false)

        assertNull(cache.load())
        assertNull(writes.single())
    }
}
