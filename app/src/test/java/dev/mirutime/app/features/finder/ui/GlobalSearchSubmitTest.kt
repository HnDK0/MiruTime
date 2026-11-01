package dev.mirutime.app.features.finder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Global search fans out only on an explicit submit.
// Typing edits `field`; until submit() runs there is no fan-out key, so no
// provider request exists per keystroke. Explicit submit is immediate.
class GlobalSearchSubmitTest {

    @Test
    fun `typing alone leaves the fan-out key empty`() {
        assertNull(globalSearchRequestQuery(null))
    }

    @Test
    fun `explicit submit produces exactly one fan-out query`() {
        val submitted = submitGlobalQuery("  dune  ")
        assertEquals("dune", submitted)
        assertEquals("dune", globalSearchRequestQuery(submitted))
    }

    @Test
    fun `blank submit is ignored`() {
        assertNull(submitGlobalQuery("   "))
    }
}
