package dev.mirutime.feature.reader.api

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterConfigKeyCacheTest {
    private val mode = ReaderTranslationMode(source = "en", target = "ru", provider = "GEMINI")

    private fun document(url: String, text: String = "text") = ReaderDocument(
        bookUrl = "book",
        chapterUrl = url,
        chapterIndex = 1,
        title = "Chapter",
        blocks = listOf(ReaderBlock(BlockId(url, 0), BlockKind.Paragraph, text)),
    )

    @Test
    fun `the same document instance is hashed once`() {
        val cache = ChapterConfigKeyCache()
        val document = document("ch-1")
        var computed = 0
        repeat(3) {
            val key = cache.keyFor(document, mode, "prompt") {
                computed++
                "key-${it.chapterUrl}"
            }
            assertEquals("key-ch-1", key)
        }

        assertEquals(1, computed)
    }

    @Test
    fun `a new parse of the same chapter is hashed again`() {
        val cache = ChapterConfigKeyCache()
        var computed = 0
        cache.keyFor(document("ch-1", "old"), mode, "prompt") { computed++; "old" }
        cache.keyFor(document("ch-1", "new"), mode, "prompt") { computed++; "new" }

        assertEquals(2, computed)
    }

    @Test
    fun `a mode or prompt change is hashed again`() {
        val cache = ChapterConfigKeyCache()
        val document = document("ch-1")
        var computed = 0
        cache.keyFor(document, mode, "prompt") { computed++; "a" }
        cache.keyFor(document, mode.copy(target = "de"), "prompt") { computed++; "b" }
        cache.keyFor(document, mode, "other-prompt") { computed++; "c" }

        assertEquals(3, computed)
    }

    @Test
    fun `an untranslatable document keeps its null key`() {
        val cache = ChapterConfigKeyCache()
        val document = document("ch-1")
        var computed = 0
        repeat(2) {
            assertNull(cache.keyFor(document, null, "") { computed++; null })
        }

        assertEquals(1, computed)
    }

    @Test
    fun `retain drops chapters that are no longer loaded`() {
        val cache = ChapterConfigKeyCache()
        val kept = document("ch-1")
        val dropped = document("ch-2")
        cache.keyFor(kept, mode, "prompt") { "kept" }
        cache.keyFor(dropped, mode, "prompt") { "dropped" }
        cache.retain(setOf("ch-1"))

        var computed = 0
        cache.keyFor(dropped, mode, "prompt") { computed++; "again" }

        assertEquals(1, computed)
    }
}
