package dev.mirutime.core.installer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequireLibScannerTest {

    @Test
    fun `scans literal require_lib calls in both quote styles`() {
        val code = """
            local a = require_lib("urls")
            local b = require_lib('html')
            local c = require_lib( "urls" )
        """.trimIndent()

        assertEquals(listOf("urls", "html"), scanRequireLibs(code))
    }

    @Test
    fun `dynamic require_lib is not treated as a dependency`() {
        val code = """
            local name = "urls"
            local lib = require_lib(name)
        """.trimIndent()

        assertEquals(emptyList(), scanRequireLibs(code))
    }

    @Test
    fun `closure is transitive and dependency-first`() {
        val sources = mapOf(
            "a" to """local b = require_lib("b")""",
            "b" to """local c = require_lib("c")""",
            "c" to "return {}",
            "unrelated" to "return {}",
        )

        val closure = runBlocking {
            resolveRequireLibClosure(listOf("""local a = require_lib("a")""")) { sources[it] }
        }

        assertEquals(listOf("c", "b", "a"), closure.order)
        assertEquals(emptyList(), closure.missing)
        assertEquals(emptyList(), closure.cycles)
    }

    @Test
    fun `missing dependency is reported and does not block the rest`() {
        val sources = mapOf("present" to "return {}")

        val closure = runBlocking {
            resolveRequireLibClosure(
                listOf("""local x = require_lib("absent"); local y = require_lib("present")"""),
            ) { sources[it] }
        }

        assertEquals(listOf("present"), closure.order)
        assertEquals(listOf("absent"), closure.missing)
    }

    @Test
    fun `cycle is reported once and the scan terminates`() {
        val sources = mapOf(
            "a" to """require_lib("b")""",
            "b" to """require_lib("a")""",
        )

        val closure = runBlocking {
            resolveRequireLibClosure(listOf("""require_lib("a")""")) { sources[it] }
        }

        assertEquals(listOf("b", "a"), closure.order)
        assertEquals(listOf(listOf("a", "b", "a")), closure.cycles)
        assertTrue(closure.missing.isEmpty())
    }
}
