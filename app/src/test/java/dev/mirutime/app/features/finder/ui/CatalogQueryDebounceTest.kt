package dev.mirutime.app.features.finder.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Catalogue search waits 1.5 s of idle
// typing, so rapid keystrokes produce ONE applied query / provider request.
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogQueryDebounceTest {

    @Test
    fun `debounce window is 1_5s`() {
        assertEquals(1_500L, CatalogSearchDebounceMillis)
    }

    @Test
    fun `rapid typing applies only the last query after 1_5s`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("d", "")
        debouncer.onInput("du", "")
        debouncer.onInput("dun", "")
        debouncer.onInput("dune", "")

        advanceTimeBy(1_499)
        runCurrent()
        assertTrue("no request before the idle window", applied.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("dune"), applied)
    }

    @Test
    fun `empty input applies immediately and cancels the pending query`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("dune", "")
        // Clear button / backspace-to-empty: restore the plain catalog now.
        debouncer.applyNow("")
        assertEquals(listOf(""), applied)

        advanceTimeBy(1_500L * 4)
        runCurrent()
        assertEquals("the pending 'dune' never fires", listOf(""), applied)
    }

    @Test
    fun `IME search applies the current input immediately and cancels the pending query`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("dun", "")
        debouncer.applyNow("dune")
        assertEquals(listOf("dune"), applied)

        advanceTimeBy(1_500L * 4)
        runCurrent()
        assertEquals("the pending 'dun' never fires", listOf("dune"), applied)
    }

    @Test
    fun `cancel drops the pending query`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("dune", "")
        debouncer.cancel()

        advanceTimeBy(1_500L * 4)
        runCurrent()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `input equal to the applied query schedules no request`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("dune", "dune")

        advanceTimeBy(1_500L * 4)
        runCurrent()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `retyping the applied query does not duplicate the request`() = runTest {
        val applied = mutableListOf<String>()
        val debouncer = CatalogQueryDebouncer(this, applied::add)

        debouncer.onInput("dune", "")
        advanceTimeBy(1_500L)
        runCurrent()

        debouncer.onInput("dune", "dune")
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(listOf("dune"), applied)
    }
}
