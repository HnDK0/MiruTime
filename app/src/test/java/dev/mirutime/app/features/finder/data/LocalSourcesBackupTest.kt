package dev.mirutime.app.features.finder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryLocalFolderStore(
    private val values: MutableMap<String, Set<String>> = mutableMapOf(),
) : LocalFolderStore {
    override fun get(kind: String): Set<String> = values[kind].orEmpty()

    override fun put(kind: String, values: Set<String>): Boolean {
        this.values[kind] = values
        return true
    }
}

private class InMemorySafRegistryStore(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : LocalSafRegistryStore {
    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun all(): Map<String, String> = values.toMap()

    override fun mergeAll(values: Map<String, String>) {
        this.values.clear()
        this.values.putAll(values)
    }
}

class LocalSourcesBackupTest {

    private val folderStore = InMemoryLocalFolderStore()
    private val folderBackup = LocalFolderBackup(folderStore)

    @Test
    fun `folder backup restores as a merge`() {
        folderStore.put("books", setOf("content://tree/books-old"))
        folderStore.put("manga", setOf("content://tree/manga-old"))

        folderBackup.restore(
            books = listOf("content://tree/books-new"),
            manga = listOf("content://tree/manga-new"),
        )

        assertEquals(
            setOf("content://tree/books-old", "content://tree/books-new"),
            folderStore.get("books"),
        )
        assertEquals(
            setOf("content://tree/manga-old", "content://tree/manga-new"),
            folderStore.get("manga"),
        )
    }

    @Test
    fun `registry restore keeps ids registered after the backup`() {
        val registry = LocalSafRegistry(InMemorySafRegistryStore())
        val first = "content://tree/first.epub"
        val second = "content://tree/second.epub"

        val firstId = registry.register(first)
        val snapshot = registry.snapshot()
        val secondId = registry.register(second)

        registry.restore(snapshot)

        assertEquals(first, registry.uriFor(firstId))
        assertEquals(second, registry.uriFor(secondId))
        assertNotNull(registry.uriFor(firstId))
    }

    @Test
    fun `restore surfaces a refused folder write`() {
        val backup = LocalFolderBackup(object : LocalFolderStore {
            override fun get(kind: String): Set<String> = emptySet()

            override fun put(kind: String, values: Set<String>): Boolean = false
        })

        assertTrue(
            runCatching { backup.restore(listOf("content://tree/books"), emptyList()) }.isFailure,
        )
    }

    @Test
    fun `registry restore keeps one id per document`() {
        val registry = LocalSafRegistry(InMemorySafRegistryStore())
        val document = "content://tree/book.epub"
        val oldId = registry.register(document)
        val newId = "0123456789abcdef0123456789abcdef"

        registry.restore(
            mapOf(
                "uri:$newId" to document,
                localSafRegistryReverseKey(document) to newId,
            ),
        )

        assertEquals(document, registry.uriFor(newId))
        assertEquals(newId, registry.idFor(document))
        assertNull(registry.uriFor(oldId))
    }
}
