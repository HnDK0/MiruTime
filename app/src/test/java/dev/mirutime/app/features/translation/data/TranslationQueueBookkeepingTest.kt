package dev.mirutime.app.features.translation.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQueueBookkeepingTest {
    @Test
    fun `a newer enqueue keeps its key when the old request releases`() {
        val queue = TranslationQueueBookkeeping()
        val old = queue.claim("k")
        val fresh = queue.claim("k")

        assertFalse(queue.release("k", old))
        assertTrue(queue.isQueued("k"))

        assertTrue(queue.release("k", fresh))
        assertFalse(queue.isQueued("k"))
    }

    @Test
    fun `remove drops the key without an owner check`() {
        val queue = TranslationQueueBookkeeping()
        queue.claim("k")

        assertTrue(queue.remove("k"))
        assertEquals(0, queue.size())
    }
}
