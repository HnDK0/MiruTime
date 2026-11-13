package dev.mirutime.app.di

import dev.mirutime.core.installer.IndexEntry
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
    fun `only the failed ids are retried and a clean retry wins`() = runBlocking {
        val libraries = listOf(entry("a"), entry("b"), entry("c"))
        val passes = mutableListOf<List<String>>()

        val failures = syncLibrariesWithRetry(libraries) { requested ->
            passes += requested.map { it.id }
            // First pass: one blip. Second pass: clean.
            if (passes.size == 1) listOf("b" to "timeout") else emptyList()
        }

        assertEquals(listOf(listOf("a", "b", "c"), listOf("b")), passes)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a library that keeps failing is reported after one retry`() = runBlocking {
        val libraries = listOf(entry("a"), entry("b"))
        val passes = mutableListOf<List<String>>()

        val failures = syncLibrariesWithRetry(libraries) { requested ->
            passes += requested.map { it.id }
            requested.filter { it.id == "b" }.map { it.id to "404" }
        }

        assertEquals(listOf(listOf("a", "b"), listOf("b")), passes)
        assertEquals(listOf("b" to "404"), failures)
    }

    @Test
    fun `declared libraries gate only the entry that needs them`() {
        val entry = IndexEntry(
            id = "p",
            name = null,
            version = null,
            url = "https://example.com/p.lua",
            sha256 = null,
            requires = listOf("catalog", "missing"),
        )

        assertEquals(listOf("missing"), missingRequires(entry, setOf("catalog", "urls")))
        assertTrue(missingRequires(entry, setOf("catalog", "missing")).isEmpty())
        // An index without `requires` (old repository) is never gated.
        assertTrue(missingRequires(entry.copy(requires = emptyList()), emptySet()).isEmpty())
    }

    @Test
    fun `batch gate holds back only entries whose libraries are absent`() {
        val ready = IndexEntry(
            id = "ready", name = null, version = null,
            url = "https://example.com/ready.lua", sha256 = null,
            requires = listOf("urls"),
        )
        val noDeps = ready.copy(id = "plain", requires = emptyList())
        val broken = ready.copy(id = "broken", requires = listOf("urls", "catalog"))

        val (allowed, blocked) = gatePendingInstalls(
            listOf(ready, noDeps, broken),
            setOf("urls"),
        )

        assertEquals(setOf("ready", "plain"), allowed)
        assertEquals(listOf("broken" to "missing libraries: catalog"), blocked)
    }

    @Test
    fun `a clean first pass does not retry`() = runBlocking {
        var calls = 0

        val failures = syncLibrariesWithRetry(listOf(entry("a"))) {
            calls++
            emptyList()
        }

        assertEquals(1, calls)
        assertTrue(failures.isEmpty())
    }
}
