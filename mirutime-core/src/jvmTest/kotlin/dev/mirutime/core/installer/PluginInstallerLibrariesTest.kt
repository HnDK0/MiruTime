package dev.mirutime.core.installer

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.engine.createLuaSession
import dev.mirutime.core.engine.loader.LibraryLoader
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.security.SsrfGuard
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PluginInstallerLibrariesTest {

    private val fs = FakeFileSystem()
    private val installDir = "plugins"
    private val libDir = "libs"
    private val downloads = mutableMapOf<String, ByteArray>()
    private val installer = PluginInstaller(
        fs, { url -> downloads[url] ?: error("404 $url") }, installDir, libDir,
        // Hostnames are accepted by the URL-only guard; literal private
        // targets are still rejected without any DNS.
        ssrfGuard = SsrfGuard(),
    )

    private val urls = "https://registry.example/libs/urls.lua"
    private val libCode = "return { version = 1 }".encodeToByteArray()

    private fun libEntry(id: String = "urls", sha256: String? = sha256Hex(libCode), url: String = urls) =
        IndexEntry(id = id, name = null, version = null, url = url, sha256 = sha256)

    @Test
    fun `installLibrary commits into libDir and one-arg require_lib resolves it`() {
        downloads[urls] = libCode

        val result = runBlocking { installer.installLibrary(libEntry()) }

        assertEquals("$libDir/urls.lua", assertIs<InstallResult.Success>(result).path)
        assertContentEquals(libCode, fs.read("$libDir/urls.lua"))

        // The engine's require_lib is 1-arg (NoveLA/corpus form) — it must
        // resolve the freshly installed library from the same libDir.
        val loader = LibraryLoader(fs, libDir)
        val session = createLuaSession("plugin", "return 1")
        loader.install(session)
        val table = (session.globals.get("require_lib") as TwoArgFunction)
            .call(LuaValue.valueOf("urls"))
        assertEquals(1, table.get("version").toint())
    }

    @Test
    fun `installLibraries installs every entry from the index libraries section`() {
        downloads[urls] = libCode
        val index = parsePluginIndex(
            """
            libraries:
              - id: "urls"
                url: "$urls"
                sha256: "${sha256Hex(libCode)}"
            """.trimIndent(),
        )

        val results = runBlocking { installer.installLibraries(index.libraries) }

        assertIs<InstallResult.Success>(results.single())
        assertContentEquals(libCode, fs.read("$libDir/urls.lua"))
    }

    @Test
    fun `library id outside the require_lib rule is rejected`() {
        for (bad in listOf("BadId", "sub/dir", "../evil")) {
            val result = runBlocking { installer.installLibrary(libEntry(id = bad)) }
            assertContains(assertIs<InstallResult.Failed>(result).reason, "invalid library id")
        }
    }

    @Test
    fun `library sha mismatch writes nothing`() {
        downloads[urls] = libCode
        val result = runBlocking { installer.installLibrary(libEntry(sha256 = "0".repeat(64))) }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "sha256 mismatch")
        assertEquals(false, fs.exists("$libDir/urls.lua"))
    }

    @Test
    fun `installLibrary rejects hostile url before download when guard present`() {
        // Default guard: literal IPs never touch DNS, so no network is needed.
        val guarded = PluginInstaller(fs, { url -> downloads[url] ?: error("404 $url") }, installDir, libDir)
        val result = runBlocking {
            guarded.installLibrary(libEntry(url = "http://169.254.169.254/urls.lua"))
        }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "SSRF blocked")
    }

    @Test
    fun `library url preflight covers scheme userinfo and literal ip`() {
        var fetches = 0
        val guarded = PluginInstaller(
            fs, { url -> fetches++; downloads[url] ?: error("404 $url") }, installDir, libDir,
        )
        val hostile = listOf(
            "ftp://registry.example/libs/urls.lua", // scheme
            "https://user:pass@registry.example/libs/urls.lua", // userinfo
            "http://10.0.0.1/libs/urls.lua", // literal private ip
        )

        for (url in hostile) {
            val result = runBlocking { guarded.installLibrary(libEntry(url = url)) }
            assertContains(assertIs<InstallResult.Failed>(result).reason, "SSRF blocked")
        }
        assertEquals(0, fetches)
    }

    // A non-default port is not an address decision (SsrfGuard has no port
    // rule): a registry on :8443 installs like any other host.
    @Test
    fun `library url on a non-default port installs`() {
        val url = "https://registry.example:8443/libs/urls.lua"
        downloads[url] = libCode

        val result = runBlocking { installer.installLibrary(libEntry(url = url)) }

        assertEquals("$libDir/urls.lua", assertIs<InstallResult.Success>(result).path)
        assertContentEquals(libCode, fs.read("$libDir/urls.lua"))
    }

    @Test
    fun `library preflight runs before the installed-sha shortcut`() {
        downloads[urls] = libCode
        assertIs<InstallResult.Success>(runBlocking { installer.installLibrary(libEntry()) })

        // Same content is installed, so the sha shortcut would normally return
        // Success before any URL check; a hostile URL must still be refused.
        val result = runBlocking {
            installer.installLibrary(libEntry(url = "http://127.0.0.1/urls.lua"))
        }

        assertContains(assertIs<InstallResult.Failed>(result).reason, "SSRF blocked")
    }

    @Test
    fun `second install of the same sha performs no network fetch`() {
        downloads[urls] = libCode
        var fetches = 0
        val counting = PluginInstaller(
            fs, { url -> fetches++; downloads[url] ?: error("404 $url") }, installDir, libDir,
            ssrfGuard = SsrfGuard(),
        )

        assertIs<InstallResult.Success>(runBlocking { counting.installLibrary(libEntry()) })
        assertEquals(1, fetches)

        val second = runBlocking { counting.installLibrary(libEntry()) }

        assertEquals("$libDir/urls.lua", assertIs<InstallResult.Success>(second).path)
        assertEquals(1, fetches)
    }

    @Test
    fun `nested require_lib resolves libraries installed into libDir`() {
        LibraryLoader.clearMemoForTests()
        val innerCode = """return { name = "inner" }""".encodeToByteArray()
        val outerCode = """
            local inner = require_lib("inner")
            return { name = inner.name .. "-outer" }
        """.trimIndent().encodeToByteArray()
        val innerUrl = "https://registry.example/libs/inner.lua"
        val outerUrl = "https://registry.example/libs/outer.lua"
        downloads[innerUrl] = innerCode
        downloads[outerUrl] = outerCode

        runBlocking {
            assertIs<InstallResult.Success>(
                installer.installLibrary(libEntry(id = "inner", sha256 = sha256Hex(innerCode), url = innerUrl)),
            )
            assertIs<InstallResult.Success>(
                installer.installLibrary(libEntry(id = "outer", sha256 = sha256Hex(outerCode), url = outerUrl)),
            )
        }

        val loader = LibraryLoader(fs, libDir)
        val session = createLuaSession("plugin", "return 1")
        loader.install(session)
        val outer = (session.globals.get("require_lib") as TwoArgFunction)
            .call(LuaValue.valueOf("outer"))
        assertEquals("inner-outer", outer.get("name").tojstring())
    }

    @Test
    fun `source install attaches metadata parsed from the script header`() {
        val url = "https://registry.example/komiku.lua"
        val code = """
            id = "komiku"
            name = "Komiku"
            content_type = "manga"
            function getCatalogList(index) return { items = {} } end
        """.trimIndent().encodeToByteArray()
        downloads[url] = code

        val result = runBlocking {
            installer.install(IndexEntry(id = "komiku", name = "Komiku", version = null, url = url, sha256 = sha256Hex(code)))
        }

        val meta = assertIs<InstallResult.Success>(result).metadata!!
        assertEquals("komiku", meta.id)
        assertEquals("manga", meta.contentType)
        assertEquals(ContentType.MANGA, meta.toPluginMetadata("komiku", 1, sha256Hex(code)).contentType)
    }
}
