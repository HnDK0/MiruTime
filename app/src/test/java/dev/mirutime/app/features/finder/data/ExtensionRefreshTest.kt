package dev.mirutime.app.features.finder.data

import dev.mirutime.app.di.REPO_FAILURE_KEY
import dev.mirutime.app.di.RepoCatalog
import dev.mirutime.core.installer.IndexEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRefreshTest {

    @Test
    fun `refresh installs the fresh catalog libraries before merging rows`() = runTest {
        var synced: List<IndexEntry>? = null
        val fresh = RepoCatalog(
            entries = listOf(entry("alpha")),
            libraries = listOf(entry("urls", sha256 = "abc")),
        )

        val result = refreshExtensionsWithLibraries(
            installed = emptyList(),
            fresh = fresh,
            cached = null,
            syncLibraries = { libraries ->
                synced = libraries
                emptyList()
            },
        )

        assertEquals(listOf("urls"), synced?.map { it.id })
        assertEquals(listOf("alpha"), result.extensions.map { it.id })
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `refresh syncs cached libraries when the fetch failed`() = runTest {
        var synced: List<IndexEntry>? = null
        val fresh = RepoCatalog(error = "offline")
        val cached = RepoCatalog(
            entries = listOf(entry("beta")),
            libraries = listOf(entry("urls", sha256 = "abc")),
        )

        val result = refreshExtensionsWithLibraries(
            installed = emptyList(),
            fresh = fresh,
            cached = cached,
            syncLibraries = { libraries ->
                synced = libraries
                emptyList()
            },
        )

        assertEquals(listOf("urls"), synced?.map { it.id })
        assertEquals(listOf("beta"), result.extensions.map { it.id })
        assertEquals(listOf(REPO_FAILURE_KEY to "offline"), result.failed)
    }

    @Test
    fun `library failure is surfaced and rows are still returned`() = runTest {
        val fresh = RepoCatalog(
            entries = listOf(entry("alpha")),
            libraries = listOf(entry("urls", sha256 = "abc")),
        )

        val result = refreshExtensionsWithLibraries(
            installed = emptyList(),
            fresh = fresh,
            cached = null,
            syncLibraries = { listOf("urls" to "offline") },
        )

        assertEquals(listOf("urls" to "offline"), result.failed)
        assertEquals(listOf("alpha"), result.extensions.map { it.id })
    }

    private fun entry(id: String, sha256: String? = null) = IndexEntry(
        id = id,
        name = id,
        version = "1.0.0",
        url = "https://example.com/$id.lua",
        sha256 = sha256,
    )
}
