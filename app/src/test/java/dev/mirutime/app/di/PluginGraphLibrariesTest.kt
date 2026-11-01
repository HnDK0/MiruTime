package dev.mirutime.app.di

import dev.mirutime.core.installer.IndexEntry
import dev.mirutime.core.installer.InstallResult
import dev.mirutime.core.installer.parsePluginIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginGraphLibrariesTest {

    private fun entry(id: String, sha256: String? = null) = IndexEntry(
        id = id,
        name = null,
        version = null,
        url = "https://example.com/$id.lua",
        sha256 = sha256,
    )

    private fun fetchFrom(scripts: Map<String, String>): suspend (String) -> ByteArray = { url ->
        val id = url.substringAfterLast('/').removeSuffix(".lua")
        scripts[id]?.encodeToByteArray() ?: error("404 $url")
    }

    @Test
    fun `catalog keeps root and language-index libraries`() {
        val root = parsePluginIndex(
            """
            languages:
              ru:
                url: "https://example.com/ru.yaml"
            libraries:
              - id: "urls"
                url: "https://example.com/urls.lua"
                sha256: "root"
            """.trimIndent(),
        )
        val loads = listOf(
            LanguageLoad(
                entries = listOf(entry("ru.source")),
                libraries = listOf(entry("lang_lib")),
                error = null,
            ),
            LanguageLoad(
                entries = emptyList(),
                // Same id as the root entry: the root copy wins.
                libraries = listOf(entry("urls")),
                error = null,
            ),
        )

        val catalog = mergeRepoCatalog(root, loads)

        assertEquals(listOf("urls", "lang_lib"), catalog.libraries.map { it.id })
        assertEquals("root", catalog.libraries.first().sha256)
        assertEquals(listOf("ru.source"), catalog.entries.map { it.id })
        assertEquals(null, catalog.error)
    }

    @Test
    fun `failed language keeps the libraries that already arrived`() {
        val root = parsePluginIndex(
            """
            languages:
              ru:
                url: "https://example.com/ru.yaml"
            libraries:
              - id: "urls"
                url: "https://example.com/urls.lua"
                sha256: "root"
            """.trimIndent(),
        )

        val catalog = mergeRepoCatalog(
            root,
            listOf(
                LanguageLoad(emptyList(), listOf(entry("lang_lib")), null),
                LanguageLoad(emptyList(), emptyList(), "ru: offline"),
            ),
        )

        assertEquals(listOf("urls", "lang_lib"), catalog.libraries.map { it.id })
        assertEquals("ru: offline", catalog.error)
    }

    @Test
    fun `matching installed sha is not pending`() {
        val installed = mapOf("urls" to "abc")
        assertEquals(
            emptyList<IndexEntry>(),
            librariesNeedingInstall(listOf(entry("urls", "abc"))) { installed[it] },
        )
        // Case-insensitive like the rest of the installer.
        assertEquals(
            emptyList<IndexEntry>(),
            librariesNeedingInstall(listOf(entry("urls", "ABC"))) { installed[it] },
        )
    }

    @Test
    fun `changed missing and sha-less libraries are pending`() {
        val installed = mapOf("urls" to "old")
        val pending = librariesNeedingInstall(
            listOf(
                entry("urls", "new"),
                entry("missing", "abc"),
                entry("unhashed", null),
            ),
        ) { installed[it] }

        assertEquals(listOf("urls", "missing", "unhashed"), pending.map { it.id })
    }

    @Test
    fun `closure includes direct and transitive libraries but no unrelated one`() = runBlocking {
        val scripts = mapOf(
            "a" to """local b = require_lib("b")""",
            "b" to "return {}",
        )
        val libraries = listOf(entry("a"), entry("b"), entry("unused"))

        val closure = resolveLibraryClosure(
            scripts = listOf("""local a = require_lib("a")"""),
            libraries = libraries,
            fetch = fetchFrom(scripts),
        )

        assertEquals(listOf("b", "a"), closure.entries.map { it.id })
        assertTrue(closure.failures.isEmpty())
    }

    @Test
    fun `missing require_lib is reported`() = runBlocking {
        val closure = resolveLibraryClosure(
            scripts = listOf("""require_lib("absent")"""),
            libraries = emptyList(),
            fetch = fetchFrom(emptyMap()),
        )

        assertTrue(closure.entries.isEmpty())
        assertEquals("absent", closure.failures.single().first)
    }

    @Test
    fun `library cycle is reported`() = runBlocking {
        val scripts = mapOf(
            "a" to """require_lib("b")""",
            "b" to """require_lib("a")""",
        )
        val closure = resolveLibraryClosure(
            scripts = listOf("""require_lib("a")"""),
            libraries = listOf(entry("a"), entry("b")),
            fetch = fetchFrom(scripts),
        )

        assertEquals(listOf("b", "a"), closure.entries.map { it.id })
        assertTrue(closure.failures.any { it.second == "library cycle detected" })
    }

    @Test
    fun `installed-only refresh syncs just the installed closure`() = runBlocking {
        val scripts = mapOf("a" to "return {}")
        val installedScripts = listOf("""require_lib("a")""")
        val libraries = listOf(entry("a"), entry("unused"))
        var synced: List<IndexEntry>? = null

        val failures = syncInstalledLibraryClosure(
            pluginScripts = installedScripts,
            libraries = libraries,
            fetch = fetchFrom(scripts),
            install = { entries ->
                synced = entries
                emptyList()
            },
        )

        assertEquals(listOf("a"), synced?.map { it.id })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `installed-only refresh without installed scripts downloads nothing`() = runBlocking {
        var synced: List<IndexEntry>? = null
        val failures = syncInstalledLibraryClosure(
            pluginScripts = emptyList(),
            libraries = listOf(entry("a")),
            fetch = fetchFrom(emptyMap()),
            install = { entries ->
                synced = entries
                emptyList()
            },
        )

        assertEquals(null, synced)
        assertTrue(failures.isEmpty())
    }

    // Fail-closed: a plugin whose library closure cannot be satisfied must not
    // be committed. Otherwise it looks installed and breaks only at load time.

    @Test
    fun `missing required library blocks the commit`() = runBlocking {
        var committed = false
        val result = installWithLibraries(
            scriptCode = """require_lib("absent")""",
            libraries = emptyList(),
            fetch = fetchFrom(emptyMap()),
            installLibraries = { emptyList() },
            commit = {
                committed = true
                InstallResult.Success("lua_plugins/p.lua", null)
            },
        )

        assertTrue(!committed)
        val reason = (result as InstallResult.Failed).reason
        assertTrue(reason, reason.contains("absent"))
    }

    @Test
    fun `library cycle blocks the commit`() = runBlocking {
        val scripts = mapOf(
            "a" to """require_lib("b")""",
            "b" to """require_lib("a")""",
        )
        var committed = false
        val result = installWithLibraries(
            scriptCode = """require_lib("a")""",
            libraries = listOf(entry("a"), entry("b")),
            fetch = fetchFrom(scripts),
            installLibraries = { emptyList() },
            commit = {
                committed = true
                InstallResult.Success("lua_plugins/p.lua", null)
            },
        )

        assertTrue(!committed)
        val reason = (result as InstallResult.Failed).reason
        assertTrue(reason, reason.contains("cycle"))
    }

    @Test
    fun `failed library install blocks the commit`() = runBlocking {
        var committed = false
        var installedLibraries: List<IndexEntry>? = null
        val result = installWithLibraries(
            scriptCode = """require_lib("a")""",
            libraries = listOf(entry("a")),
            fetch = fetchFrom(mapOf("a" to "return {}")),
            installLibraries = { libraries ->
                installedLibraries = libraries
                listOf("a" to "offline")
            },
            commit = {
                committed = true
                InstallResult.Success("lua_plugins/p.lua", null)
            },
        )

        assertEquals(listOf("a"), installedLibraries?.map { it.id })
        assertTrue(!committed)
        val reason = (result as InstallResult.Failed).reason
        assertTrue(reason, reason.contains("offline"))
    }

    @Test
    fun `satisfied closure commits the script`() = runBlocking {
        var committed = false
        val result = installWithLibraries(
            scriptCode = """require_lib("a")""",
            libraries = listOf(entry("a")),
            fetch = fetchFrom(mapOf("a" to "return {}")),
            installLibraries = { emptyList() },
            commit = {
                committed = true
                InstallResult.Success("lua_plugins/p.lua", null)
            },
        )

        assertTrue(committed)
        assertTrue(result is InstallResult.Success)
    }
}
