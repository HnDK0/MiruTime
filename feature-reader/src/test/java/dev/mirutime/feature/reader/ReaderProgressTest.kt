package dev.mirutime.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderProgressTest {
    @Test
    fun `saves and restores per book`() {
        val store = ReaderProgressStore()
        val p = ReaderProgress(chapterIndex = 3, itemIndex = 7, offsetPx = 42, splitSentences = false)
        store.save("fake://a/book", p)
        assertEquals(p, store.load("fake://a/book"))
        assertNull(store.load("fake://a/other"))
    }

    @Test
    fun `progress percent uses item count`() {
        assertEquals(0f, progressPercent(0, 0), 0.001f)
        assertEquals(50f, progressPercent(4, 10), 0.001f)
        assertEquals(100f, progressPercent(9, 10), 0.001f)
    }

    @Test
    fun `restore target only matches the same chapter and split mode`() {
        val saved = ReaderProgress(chapterIndex = 3, itemIndex = 7, offsetPx = 42, splitSentences = false)
        assertEquals(7, restoreItemIndex(saved, chapterIndex = 3, splitSentences = false, itemCount = 10))
        assertEquals(0, restoreItemIndex(saved, chapterIndex = 4, splitSentences = false, itemCount = 10))
        assertEquals(0, restoreItemIndex(saved, chapterIndex = 3, splitSentences = true, itemCount = 10))
        assertEquals(9, restoreItemIndex(saved.copy(itemIndex = 99), chapterIndex = 3, splitSentences = false, itemCount = 10))
    }

    @Test
    fun `clearing removes the entry`() {
        val store = ReaderProgressStore()
        store.save("b", ReaderProgress(1, 0, 0, true))
        store.clear("b")
        assertNull(store.load("b"))
    }
}
