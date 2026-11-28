package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What crosses LuaSession.call(): errors that must come back as results, and
// the Lua table -> LuaData conversion.
class LuaSessionBoundaryTest {

    private fun call(script: String, fn: String = "main"): LuaCallResult =
        createLuaSession("boundary", script).call(fn, emptyList())

    // Unbounded recursion needs a stack to overflow; a small one keeps the test fast.
    private fun <T> onSmallStack(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "small-stack", 256 * 1024)
        thread.start()
        thread.join()
        return checkNotNull(result) { "the thread did not run" }.getOrThrow()
    }

    // -- stack overflow --

    @Test
    fun `unbounded recursion comes back as a script error`() {
        val result = onSmallStack {
            call(
                """
                local function f() return 1 + f() end
                function main() return f() end
                """.trimIndent(),
            )
        }
        assertNull(result.data)
        val error = result.error
        assertTrue(error.toString(), error is PluginError.ScriptError)
        assertTrue((error as PluginError.ScriptError).detail.contains("stack overflow"))
    }

    @Test
    fun `a session keeps working after a stack overflow`() {
        val (overflow, after) = onSmallStack {
            val session = createLuaSession(
                "boundary",
                """
                local function f() return 1 + f() end
                function loop() return f() end
                function ok() return 42 end
                """.trimIndent(),
            )
            session.call("loop", emptyList()) to session.call("ok", emptyList())
        }
        assertNotNull(overflow.error)
        assertNull(after.error)
        assertEquals(LuaData.LuaNum(42.0), after.data)
    }

    // -- table -> LuaData --

    @Test
    fun `a sequence table is a list`() {
        val result = call("""function main() return { "a", "b" } end""")
        assertEquals(LuaData.LuaList(listOf(LuaData.LuaStr("a"), LuaData.LuaStr("b"))), result.data)
    }

    @Test
    fun `a sequence assigned out of order is still a list`() {
        val result = call("""function main() return { [2] = "b", [1] = "a" } end""")
        assertEquals(LuaData.LuaList(listOf(LuaData.LuaStr("a"), LuaData.LuaStr("b"))), result.data)
    }

    @Test
    fun `numeric string keys stay a map with their values`() {
        val result = call("""function main() return { ["1"] = "a", ["2"] = "b" } end""")
        assertEquals(
            LuaData.LuaMap(mapOf("1" to LuaData.LuaStr("a"), "2" to LuaData.LuaStr("b"))),
            result.data,
        )
    }

    @Test
    fun `a fractional key stays a map with its value`() {
        val result = call("""function main() return { [1.5] = "x" } end""")
        assertEquals(LuaData.LuaMap(mapOf("1.5" to LuaData.LuaStr("x"))), result.data)
    }

    @Test
    fun `a table with a gap is a map`() {
        val result = call("""function main() return { [1] = "a", [3] = "c" } end""")
        assertEquals(
            LuaData.LuaMap(mapOf("1" to LuaData.LuaStr("a"), "3" to LuaData.LuaStr("c"))),
            result.data,
        )
    }

    // -- script text --

    @Test
    fun `a script saved with a byte order mark loads`() {
        val result = call("﻿function main() return 7 end")
        assertNull(result.error)
        assertEquals(LuaData.LuaNum(7.0), result.data)
    }
}
