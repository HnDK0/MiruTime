package dev.mirutime.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSelectionTest {

    private val ordered = listOf("a", "b", "c", "d", "e", "f", "g")

    @Test
    fun `first long press activates selection and anchors on that item`() {
        val state = RangeSelection<String>().longPress("c", ordered)
        assertTrue(state.active)
        assertEquals(setOf("c"), state.selected)
        assertEquals("c", state.anchor)
    }

    @Test
    fun `second long press selects downward and upward inclusive ranges`() {
        val downward = RangeSelection<String>()
            .longPress("b", ordered)
            .longPress("e", ordered)
        assertEquals(setOf("b", "c", "d", "e"), downward.selected)
        assertEquals("e", downward.anchor)

        val upward = RangeSelection<String>()
            .longPress("f", ordered)
            .longPress("c", ordered)
        assertEquals(setOf("c", "d", "e", "f"), upward.selected)
        assertEquals("c", upward.anchor)
    }

    @Test
    fun `chained long press extends from moved anchor while keeping earlier items`() {
        val state = RangeSelection<String>()
            .longPress("a", ordered)
            .longPress("c", ordered)
            .longPress("f", ordered)
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), state.selected)
        assertEquals("f", state.anchor)
    }

    @Test
    fun `tap on updates anchor for subsequent range and tap off on anchor clears it`() {
        val toggledOn = RangeSelection<String>()
            .longPress("a", ordered)
            .toggle("d")
            .longPress("f", ordered)
        assertEquals(setOf("a", "d", "e", "f"), toggledOn.selected)

        val toggledOffAnchor = RangeSelection<String>()
            .longPress("a", ordered)
            .longPress("c", ordered)
            .toggle("c")
            .longPress("f", ordered)
        assertNull(RangeSelection<String>().longPress("a", ordered).toggle("a").anchor)
        assertEquals(setOf("a", "b", "f"), toggledOffAnchor.selected)
        assertEquals("f", toggledOffAnchor.anchor)
    }

    @Test
    fun `missing anchor in filtered order adds only target and re-anchors`() {
        val state = RangeSelection<String>()
            .longPress("b", ordered)
            .longPress("f", listOf("d", "e", "f"))
        assertEquals(setOf("b", "f"), state.selected)
        assertEquals("f", state.anchor)
    }

    @Test
    fun `clear vs autoDeactivateWhenEmpty and prune behave consistently`() {
        val emptied = RangeSelection<String>()
            .longPress("a", ordered)
            .toggle("a", autoDeactivateWhenEmpty = true)
        assertFalse(emptied.active)
        assertTrue(emptied.isEmpty)

        val kept = RangeSelection<String>()
            .longPress("a", ordered)
            .clear(keepActive = true)
        assertTrue(kept.active)
        assertTrue(kept.isEmpty)
        assertNull(kept.anchor)

        val pruned = RangeSelection<String>()
            .longPress("a", ordered)
            .longPress("d", ordered)
            .prune(validKeys = setOf("b", "c"), autoDeactivateWhenEmpty = true)
        assertTrue(pruned.active)
        assertEquals(setOf("b", "c"), pruned.selected)
        assertNull(pruned.anchor)

        val prunedEmpty = pruned.prune(validKeys = setOf("g"), autoDeactivateWhenEmpty = true)
        assertFalse(prunedEmpty.active)
        assertTrue(prunedEmpty.isEmpty)

        val prunedKeepActive = RangeSelection<String>()
            .longPress("a", ordered)
            .prune(validKeys = setOf("g"), autoDeactivateWhenEmpty = false)
        assertTrue(prunedKeepActive.active)
        assertTrue(prunedKeepActive.isEmpty)
        assertNull(prunedKeepActive.anchor)
    }

    @Test
    fun `toggle off on non-anchor preserves anchor and inactive toggle is a no-op`() {
        val inactive = RangeSelection<String>().toggle("a")
        assertFalse(inactive.active)
        assertTrue(inactive.isEmpty)

        val state = RangeSelection<String>()
            .longPress("b", ordered)
            .longPress("e", ordered)
            .toggle("c")
            .longPress("g", ordered)
        assertEquals(setOf("b", "d", "e", "f", "g"), state.selected)
        assertEquals("g", state.anchor)
    }

    @Test
    fun `selectAll supports replacement and additive modes while clearing anchor`() {
        val inactive = RangeSelection<String>().selectAll(ordered)
        assertFalse(inactive.active)

        val replaced = RangeSelection<String>()
            .longPress("a", ordered)
            .selectAll(listOf("c", "d"), additive = false)
        assertEquals(setOf("c", "d"), replaced.selected)
        assertNull(replaced.anchor)

        val additive = RangeSelection<String>()
            .longPress("a", ordered)
            .selectAll(listOf("c", "d"), additive = true)
        assertEquals(setOf("a", "c", "d"), additive.selected)
        assertNull(additive.anchor)
    }

    @Test
    fun `longPress fallback handles missing target empty order and same anchor safely`() {
        val missingTarget = RangeSelection<String>()
            .longPress("a", ordered)
            .longPress("z", ordered)
        assertEquals(setOf("a", "z"), missingTarget.selected)
        assertEquals("z", missingTarget.anchor)

        val emptyOrder = RangeSelection<String>()
            .longPress("a", emptyList())
            .longPress("d", emptyList())
        assertEquals(setOf("a", "d"), emptyOrder.selected)
        assertEquals("d", emptyOrder.anchor)

        val sameAnchor = RangeSelection<String>()
            .longPress("c", ordered)
            .longPress("c", ordered)
        assertEquals(setOf("c"), sameAnchor.selected)
        assertEquals("c", sameAnchor.anchor)
    }

    @Test
    fun `clear keepActive false and close deactivate selection`() {
        val active = RangeSelection<String>().longPress("a", ordered)
        val clearedClosed = active.clear(keepActive = false)
        assertFalse(clearedClosed.active)
        assertTrue(clearedClosed.isEmpty)
        assertNull(clearedClosed.anchor)

        val closed = active.close()
        assertFalse(closed.active)
        assertTrue(closed.isEmpty)
        assertNull(closed.anchor)

        // Ensure helper completes cleanly with debug logging disabled.
        logRangeSelect("test", 1, 2)
    }
}
