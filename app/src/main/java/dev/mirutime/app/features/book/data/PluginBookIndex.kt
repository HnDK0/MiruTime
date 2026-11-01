package dev.mirutime.app.features.book.data

import android.content.Context
import android.content.SharedPreferences

// Maps a bookUrl to the plugin (sourceId) that serves it, plus its display
// title. The book route carries only the bookUrl, and the shell header reads a
// title synchronously, so both need a lookup that survives process death:
// Android restores the navigation back stack after the process is killed.
// Populated by Finder when a catalogue/search page is mapped and by the book
// provider on load.
object PluginBookIndex {
    // Plugin searches run concurrently; every map/prefs access goes through this
    // lock. SharedPreferences reads are in-memory and apply() only enqueues the
    // disk write, so nothing slow is held under the lock.
    private val lock = Any()
    private val sourceByBook = LinkedHashMap<String, String>()
    private val titleByBook = LinkedHashMap<String, String>()
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        }
    }

    fun put(bookUrl: String, sourceId: String) {
        if (bookUrl.isBlank() || sourceId.isBlank()) return
        synchronized(lock) {
            boundedPut(sourceByBook, bookUrl, sourceId)
            prefs?.edit()?.putString("$SourcePrefix$bookUrl", sourceId)?.apply()
        }
    }

    fun sourceFor(bookUrl: String): String? = synchronized(lock) {
        sourceByBook[bookUrl] ?: prefs?.getString("$SourcePrefix$bookUrl", null)
    }

    fun putTitle(bookUrl: String, title: String) {
        if (bookUrl.isBlank() || title.isBlank()) return
        synchronized(lock) {
            boundedPut(titleByBook, bookUrl, title)
            prefs?.edit()?.putString("$TitlePrefix$bookUrl", title)?.apply()
        }
    }

    fun titleFor(bookUrl: String): String? = synchronized(lock) {
        titleByBook[bookUrl] ?: prefs?.getString("$TitlePrefix$bookUrl", null)
    }

    // Test seam / full reset.
    fun clear() = synchronized(lock) {
        sourceByBook.clear()
        titleByBook.clear()
        prefs?.edit()?.clear()?.apply()
    }

    // Caller must hold [lock].
    private fun boundedPut(map: LinkedHashMap<String, String>, key: String, value: String) {
        if (key !in map && map.size >= MaxEntries) {
            map.keys.firstOrNull()?.let(map::remove)
        }
        map[key] = value
    }
}

private const val PrefsName = "plugin_book_index"
private const val SourcePrefix = "s:"
private const val TitlePrefix = "t:"
private const val MaxEntries = 500
