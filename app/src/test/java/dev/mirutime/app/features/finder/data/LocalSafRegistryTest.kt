package dev.mirutime.app.features.finder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSafRegistryTest {

    private val store = mutableMapOf<String, String>()
    private val registry = LocalSafRegistry(object : LocalSafRegistryStore {
        override fun get(key: String): String? = store[key]
        override fun put(key: String, value: String) {
            store[key] = value
        }

        override fun all(): Map<String, String> = store.toMap()

        override fun mergeAll(values: Map<String, String>) {
            store.putAll(values)
        }
    })

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/one.epub"

    @Test
    fun `registry round-trips and is stable`() {
        val id = registry.register(uri)

        assertEquals(id, registry.register(uri))
        assertEquals(uri, registry.uriFor(id))
        assertEquals(id, registry.idFor(uri))
        assertEquals("local-file://$id", localCatalogUrl(uri))
    }

    @Test
    fun `unknown and legacy base64 ids are invalid`() {
        assertNull(registry.uriFor("0123456789abcdef0123456789abcdef"))
        assertNull(localFileDocumentUri("local-file://0123456789abcdef0123456789abcdef", registry))
        // Pre-fix Base64 URL: deliberately not resolvable, no fallback.
        assertNull(localFileDocumentUri("local-file://Y29udGVudDovL3Rlc3Q", registry))
        assertNull(localFileDocumentUri("local-file://", registry))
        assertNull(localFileDocumentUri("local-file://not-hex-hex-hex", registry))
    }

    @Test
    fun `collision extends the id instead of reusing another document`() {
        val base = localFileId(uri)
        store[localSafRegistryUriKey(base)] = "content://other/document"

        val id = registry.register(uri)

        assertNotEquals(base, id)
        assertEquals(base, id.take(base.length))
        assertTrue(id.length > base.length)
        assertEquals(uri, registry.uriFor(id))
        assertEquals("local-file://$id", localCatalogUrl(uri, id))
        assertFalse(localFileDocumentUri("local-file://$base", registry) == uri)
    }
}
