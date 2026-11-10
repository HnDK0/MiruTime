package dev.mirutime.app.features.translation.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochPruneTest {
    @Test
    fun `the oldest entries are dropped first`() {
        val epochs = LinkedHashMap<String, Int>()
        (1..5).forEach { epochs["c$it"] = 1 }

        pruneEpochs(epochs, inflight = emptySet(), limit = 3)

        assertEquals(listOf("c3", "c4", "c5"), epochs.keys.toList())
    }

    @Test
    fun `an in-flight chapter keeps its epoch`() {
        val epochs = LinkedHashMap<String, Int>()
        (1..3).forEach { epochs["c$it"] = 1 }

        val removed = pruneEpochs(epochs, inflight = setOf("c1"), limit = 1)

        assertTrue(epochs.containsKey("c1"))
        assertFalse(epochs.containsKey("c2"))
        assertFalse(epochs.containsKey("c3"))
        assertEquals(2, removed)
    }

    @Test
    fun `nothing is dropped under the limit`() {
        val epochs = LinkedHashMap<String, Int>()
        epochs["c1"] = 1
        assertEquals(0, pruneEpochs(epochs, inflight = emptySet(), limit = 8))
    }
}
