package dev.mirutime.app.features.finder.data

import android.content.Context
import dev.mirutime.app.features.library.data.sha256Hex

// Short, navigation-safe local-file identity. IDs are lowercase hex only, so a
// route may encode/decode them any number of times without changing the value.
internal const val LOCAL_FILE_ID_LENGTH = 32
private const val PREFS = "local_saf_registry"
private const val ID_PREFIX = "id:"
private const val URI_PREFIX = "uri:"

internal fun localFileId(documentUri: String): String =
    sha256Hex(documentUri.trim().encodeToByteArray()).take(LOCAL_FILE_ID_LENGTH)

internal fun localSafRegistryUriKey(id: String): String = "$URI_PREFIX$id"
internal fun localSafRegistryReverseKey(documentUri: String): String =
    "$ID_PREFIX${sha256Hex(documentUri.encodeToByteArray())}"

/** Minimal persistence seam; SharedPreferences in the app, a map in tests. */
interface LocalSafRegistryStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

private class SharedPrefsRegistryStore(context: Context) : LocalSafRegistryStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * Persistent short-id registry: id (hex) -> concrete SAF document URI and the
 * reverse. Unknown IDs are invalid (old Base64 catalogue URLs are deliberately
 * not resolved; they must be re-scanned).
 */
class LocalSafRegistry internal constructor(
    private val store: LocalSafRegistryStore,
) {
    constructor(context: Context) : this(SharedPrefsRegistryStore(context))

    @Synchronized
    fun register(documentUri: String): String {
        val normalized = documentUri.trim()
        if (normalized.isEmpty()) throw IllegalArgumentException("Empty document URI")
        store.get(localSafRegistryReverseKey(normalized))?.let { id ->
            if (store.get(localSafRegistryUriKey(id)) == normalized) return id
        }
        val full = sha256Hex(normalized.encodeToByteArray())
        // Normal IDs are 32 hex chars. A hash collision extends the prefix in
        // 8-char steps instead of reusing another document's identity.
        for (length in LOCAL_FILE_ID_LENGTH..full.length step 8) {
            val candidate = full.take(length)
            val existing = store.get(localSafRegistryUriKey(candidate))
            if (existing == null || existing == normalized) {
                store.put(localSafRegistryUriKey(candidate), normalized)
                store.put(localSafRegistryReverseKey(normalized), candidate)
                return candidate
            }
        }
        throw IllegalStateException("Local SAF registry collision")
    }

    fun uriFor(id: String): String? {
        val normalized = id.lowercase()
        if (!isLocalFileId(normalized)) return null
        return store.get(localSafRegistryUriKey(normalized))
    }

    fun idFor(documentUri: String): String? {
        val id = store.get(localSafRegistryReverseKey(documentUri.trim())) ?: return null
        return id.takeIf { store.get(localSafRegistryUriKey(it)) == documentUri.trim() }
    }
}

internal fun isLocalFileId(id: String): Boolean =
    id.length in LOCAL_FILE_ID_LENGTH..64 &&
        id.length % 8 == 0 &&
        id.all { it in '0'..'9' || it in 'a'..'f' }
