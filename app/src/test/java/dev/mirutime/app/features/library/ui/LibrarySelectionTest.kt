package dev.mirutime.app.features.library.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure selection rules: activation, tap-to-toggle, select-all/clear and close.
class LibrarySelectionTest {

    @Test
    fun `long press activates selection and tapping toggles`() {
        val start = reduceLibrarySelection(
            LibrarySelection(),
            LibrarySelectionAction.LongPress("a"),
        )
        assertTrue(start.active)
        assertEquals(setOf("a"), start.selected)

        val added = reduceLibrarySelection(start, LibrarySelectionAction.Toggle("b"))
        assertEquals(setOf("a", "b"), added.selected)

        val removed = reduceLibrarySelection(added, LibrarySelectionAction.Toggle("a"))
        assertEquals(setOf("b"), removed.selected)
        assertTrue(removed.active)
    }

    @Test
    fun `tap outside selection does nothing`() {
        val state = LibrarySelection()
        assertEquals(state, reduceLibrarySelection(state, LibrarySelectionAction.Toggle("a")))
    }

    @Test
    fun `select all replaces the set and ignores blanks`() {
        val active = LibrarySelection(active = true, selected = setOf("old"))
        val result = reduceLibrarySelection(
            active,
            LibrarySelectionAction.SelectAll(listOf("a", "b", "", "a")),
        )
        assertEquals(setOf("a", "b"), result.selected)
        assertTrue(result.active)
    }

    @Test
    fun `clear keeps selection mode and close exits it`() {
        val active = LibrarySelection(active = true, selected = setOf("a"))
        val cleared = reduceLibrarySelection(active, LibrarySelectionAction.Clear)
        assertTrue(cleared.active)
        assertTrue(cleared.isEmpty)

        val closed = reduceLibrarySelection(cleared, LibrarySelectionAction.Close)
        assertFalse(closed.active)
        assertTrue(closed.isEmpty)
    }

    @Test
    fun `repeated and large selections stay consistent`() {
        var state = LibrarySelection(active = true)
        repeat(5_000) { index ->
            state = reduceLibrarySelection(state, LibrarySelectionAction.Toggle("book-$index"))
        }
        assertEquals(5_000, state.count)

        state = reduceLibrarySelection(
            state,
            LibrarySelectionAction.SelectAll((0 until 20_000).map { "book-$it" }),
        )
        assertEquals(20_000, state.count)

        state = reduceLibrarySelection(state, LibrarySelectionAction.Clear)
        assertEquals(0, state.count)
        assertTrue(state.active)
    }
}
