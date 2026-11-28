package dev.mirutime.core.engine

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseUrlPinTest {

    private val script = """
        baseUrl = "https://site.example/"
        local api = baseUrl .. "api/v1"
        function apiRoot() return api end
        function pageUrl(path) return baseUrl .. path end
    """.trimIndent()

    private fun load(code: String, hostBaseUrl: String): Globals {
        val globals = createSandboxGlobals()
        globals.pinningBaseUrl(hostBaseUrl) { globals.load(code, "@test").call() }
        return globals
    }

    private fun Globals.text(name: String, vararg args: String): String =
        get(name).invoke(LuaValue.varargsOf(args.map { LuaValue.valueOf(it) }.toTypedArray())).arg1().tojstring()

    @Test
    fun `the host value replaces the declared one and what the script derives from it`() {
        val globals = load(script, "https://mirror.example/")

        assertEquals("https://mirror.example/", globals.get("baseUrl").tojstring())
        assertEquals("https://mirror.example/api/v1", globals.text("apiRoot"))
        assertEquals("https://mirror.example/x/1", globals.text("pageUrl", "x/1"))
    }

    @Test
    fun `without an override nothing changes`() {
        val globals = load(script, "https://site.example/")

        assertEquals("https://site.example/", globals.get("baseUrl").tojstring())
        assertEquals("https://site.example/api/v1", globals.text("apiRoot"))
    }

    @Test
    fun `the declared trailing slash is kept`() {
        assertEquals(
            "https://mirror.example/",
            load("baseUrl = 'https://site.example/'", "https://mirror.example").get("baseUrl").tojstring(),
        )
        assertEquals(
            "https://mirror.example",
            load("baseUrl = 'https://site.example'", "https://mirror.example/").get("baseUrl").tojstring(),
        )
    }

    // A host value can carry a path (a mirror under /proxy). The declared shape decides how the
    // script joins its paths: with a slash it appends "api", without one it appends "/api".
    @Test
    fun `a host base with a path keeps the boundary the script expects`() {
        val withSlash = load(script, "https://mirror.example/proxy/")
        assertEquals("https://mirror.example/proxy/", withSlash.get("baseUrl").tojstring())
        assertEquals("https://mirror.example/proxy/x/1", withSlash.text("pageUrl", "x/1"))

        val withoutSlash = load("baseUrl = 'https://site.example'", "https://mirror.example/proxy/")
        assertEquals("https://mirror.example/proxy", withoutSlash.get("baseUrl").tojstring())
    }

    @Test
    fun `a host value that is not an http url is ignored`() {
        assertEquals("https://site.example/", load(script, "mirror.example").get("baseUrl").tojstring())
        assertEquals("https://site.example/", load(script, "").get("baseUrl").tojstring())
    }

    @Test
    fun `only the baseUrl global is touched and the table is left as it was`() {
        val globals = createSandboxGlobals()
        val before = globals.getmetatable()

        globals.pinningBaseUrl("https://mirror.example") {
            globals.load("name = 'x'\nbaseUrl = 'https://site.example'\nbaseUrl = 'https://second.example'", "@t").call()
        }

        assertEquals("x", globals.get("name").tojstring())
        // Only the first assignment is the declaration; later ones are the script's own.
        assertEquals("https://second.example", globals.get("baseUrl").tojstring())
        assertEquals(before, globals.getmetatable())
        globals.set("later", LuaValue.valueOf(1))
        assertEquals(1, globals.get("later").toint())
    }

    @Test
    fun `a script that declares no baseUrl keeps none`() {
        assertTrue(load("name = 'x'", "https://mirror.example").get("baseUrl").isnil())
    }

    @Test
    fun `the table is restored when the script fails`() {
        val globals = createSandboxGlobals()
        try {
            globals.pinningBaseUrl("https://mirror.example") { globals.load("error('boom')", "@t").call() }
        } catch (_: Exception) {
            // expected
        }
        assertNull(globals.getmetatable())
    }

    @Test
    fun `a metatable the script installs on its own globals is kept`() {
        val globals = createSandboxGlobals()
        globals.pinningBaseUrl("https://mirror.example") {
            globals.load(
                "setmetatable(_G, { __index = function() return 'dflt' end })\nbaseUrl = 'https://site.example'",
                "@t",
            ).call()
        }

        val kept = globals.getmetatable()
        assertTrue(kept != null && kept.rawget(LuaValue.NEWINDEX).isnil())
        assertEquals("dflt", globals.load("return missing_global", "@r").call().tojstring())
    }
}
