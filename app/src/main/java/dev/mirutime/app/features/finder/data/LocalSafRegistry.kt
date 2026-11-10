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

/** Keeps one id per document URI; archive values win on conflicts. */
internal fun mergeRegistry(
    existing: Map<String, String>,
    incoming: Map<String, String>,
): Map<String, String> {
    val merged = (existing + incoming).toMutableMap()
    val keepByDocument = mutableMapOf<String, String>()
    merged.forEach { (key, documentUri) ->
        if (!key.startsWith(URI_PREFIX)) return@forEach
        val id = key.removePrefix(URI_PREFIX)
        val current = keepByDocument[documentUri]
        if (current == null || incoming.containsKey(key)) {
            keepByDocument[documentUri] = id
        }
    }
    merged.keys.filter { it.startsWith(URI_PREFIX) }.forEach { key ->
        val id = key.removePrefix(URI_PREFIX)
        val documentUri = merged[key] ?: return@forEach
        if (keepByDocument[documentUri] != id) merged.remove(key)
    }
    keepByDocument.forEach { (documentUri, id) ->
        merged[localSafRegistryReverseKey(documentUri)] = id
    }
    return merged
}

/** Minimal persistence seam; SharedPreferences in the app, a map in tests. */
interface LocalSafRegistryStore {
    fun get(key: String): String?
    fun put(key: String, value: String)

    fun all(): Map<String, String>

    /** Merge: archive values win, ids registered after the backup survive. */
    fun mergeAll(values: Map<String, String>)
}

private class SharedPrefsRegistryStore(context: Context) : LocalSafRegistryStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun all(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun mergeAll(values: Map<String, String>) {
        val existing = all()
        val merged = mergeRegistry(existing, values)
        val editor = prefs.edit()
        existing.keys.filter { it !in merged }.forEach { editor.remove(it) }
        merged.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not restore local SAF registry" }
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

    fun snapshot(): Map<String, String> = store.all()

    fun restore(values: Map<String, String>) {
        store.mergeAll(mergeRegistry(store.all(), values))
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
