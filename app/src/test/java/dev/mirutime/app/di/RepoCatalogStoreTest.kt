package dev.mirutime.app.di

import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.installer.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoCatalogStoreTest {

    private val fileSystem = MapFileSystem()
    private val store = RepoCatalogStore(fileSystem)

    @Test
    fun `disk round trip keeps entries and presentation fields`() {
        val snapshot = CachedRepoCatalog(
            repoUrl = "https://example.com/index.yaml",
            storedAt = 1_700_000_000_000L,
            catalog = RepoCatalog(
                entries = listOf(
                    entry(id = "alpha", name = "Alpha", version = "2.0.0"),
                    entry(
                        id = "beta",
                        name = "Beta",
                        version = null,
                        contentType = "manga",
                        language = "ru",
                        icon = "https://cdn.example/beta.png",
                        requires = listOf("urls", "catalog"),
                    ),
                ),
            ),
        )

        store.write(snapshot)

        val restored = store.read("https://example.com/index.yaml")
        assertNotNull(restored)
        assertEquals(snapshot.repoUrl, restored!!.repoUrl)
        assertEquals(snapshot.storedAt, restored.storedAt)
        assertEquals(listOf("alpha", "beta"), restored.catalog.entries.map { it.id })
        val beta = restored.catalog.entries.first { it.id == "beta" }
        assertEquals("Beta", beta.name)
        assertNull(beta.version)
        assertEquals("manga", beta.contentType)
        assertEquals("ru", beta.language)
        assertEquals("https://cdn.example/beta.png", beta.icon)
        assertEquals(listOf("urls", "catalog"), beta.requires)
        assertEquals("https://example.com/beta.lua", beta.url)
    }

    @Test
    fun `disk round trip keeps libraries`() {
        val snapshot = CachedRepoCatalog(
            repoUrl = "https://example.com/index.yaml",
            storedAt = 1_700_000_000_000L,
            catalog = RepoCatalog(
                entries = listOf(entry(id = "alpha")),
                libraries = listOf(
                    entry(id = "urls", name = null, version = null, contentType = null),
                ),
            ),
        )

        store.write(snapshot)

        val restored = store.read("https://example.com/index.yaml")
        assertNotNull(restored)
        assertEquals(
            listOf("urls"),
            restored!!.catalog.libraries.map { it.id },
        )
        assertEquals("https://example.com/urls.lua", restored.catalog.libraries.single().url)
        assertEquals("abc", restored.catalog.libraries.single().sha256)
    }

    @Test
    fun `legacy snapshot without libraries decodes with an empty list`() {
        fileSystem.files[REPO_CATALOG_FILE] = """
            {"repoUrl":"https://example.com/index.yaml","storedAt":1,
             "entries":[{"id":"alpha","url":"https://example.com/alpha.lua"}]}
        """.trimIndent().encodeToByteArray()

        val restored = store.read("https://example.com/index.yaml")

        assertNotNull(restored)
        assertEquals(listOf("alpha"), restored!!.catalog.entries.map { it.id })
        assertEquals(emptyList<IndexEntry>(), restored.catalog.libraries)
    }

    @Test
    fun `library-only catalog round trips`() {
        val snapshot = CachedRepoCatalog(
            repoUrl = "https://example.com/index.yaml",
            storedAt = 1L,
            catalog = RepoCatalog(
                libraries = listOf(entry(id = "urls")),
            ),
        )

        store.write(snapshot)

        val restored = store.read("https://example.com/index.yaml")
        assertNotNull(restored)
        assertEquals(emptyList<IndexEntry>(), restored!!.catalog.entries)
        assertEquals(listOf("urls"), restored.catalog.libraries.map { it.id })
    }

    @Test
    fun `repo url mismatch is a miss`() {
        store.write(
            CachedRepoCatalog(
                repoUrl = "https://example.com/old.yaml",
                storedAt = 1L,
                catalog = RepoCatalog(entries = listOf(entry(id = "alpha"))),
            ),
        )

        assertNull(store.read("https://example.com/new.yaml"))
        assertNotNull(store.read("https://example.com/old.yaml"))
    }

    @Test
    fun `corrupt and empty files are misses`() {
        fileSystem.files[REPO_CATALOG_FILE] = "this is not json".encodeToByteArray()
        assertNull(store.read("https://example.com/index.yaml"))

        fileSystem.files[REPO_CATALOG_FILE] =
            """{"repoUrl":"https://example.com/index.yaml","storedAt":1,"entries":[]}"""
                .encodeToByteArray()
        assertNull(store.read("https://example.com/index.yaml"))
    }

    @Test
    fun `an empty catalog is never written`() {
        store.write(
            CachedRepoCatalog(
                repoUrl = "https://example.com/index.yaml",
                storedAt = 1L,
                catalog = RepoCatalog(),
            ),
        )

        assertNull(fileSystem.files[REPO_CATALOG_FILE])
    }

    @Test
    fun `freshness decision uses storedAt and ttl`() {
        val now = 1_000_000L
        val snapshot = CachedRepoCatalog(
            repoUrl = "https://example.com/index.yaml",
            storedAt = now - 1_000,
            catalog = RepoCatalog(entries = listOf(entry(id = "alpha"))),
        )

        assertTrue(isRepoCatalogFresh(snapshot, ttlMs = 15_000, now = now))
        assertFalse(isRepoCatalogFresh(snapshot, ttlMs = 1_000, now = now))
        assertFalse(isRepoCatalogFresh(snapshot.copy(storedAt = now + 1), ttlMs = 15_000, now = now))
        assertFalse(isRepoCatalogFresh(null, ttlMs = 15_000, now = now))
    }

    private fun entry(
        id: String,
        name: String? = id,
        version: String? = "1.0.0",
        contentType: String? = null,
        language: String? = null,
        icon: String? = null,
        requires: List<String> = emptyList(),
    ) = IndexEntry(
        id = id,
        name = name,
        version = version,
        url = "https://example.com/$id.lua",
        sha256 = "abc",
        contentType = contentType,
        language = language,
        icon = icon,
        requires = requires,
    )

    private class MapFileSystem : FileSystem {
        val files = mutableMapOf<String, ByteArray>()

        override fun read(path: String): ByteArray? = files[path]

        override fun write(path: String, data: ByteArray) {
            files[path] = data
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun delete(path: String) {
            files.remove(path)
        }

        override fun rename(from: String, to: String) {
            files[from]?.let { files[to] = it }
            files.remove(from)
        }

        override fun list(dir: String): List<String> =
            files.keys.filter { it.startsWith("$dir/") }.sorted()
    }
}
