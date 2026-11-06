package dev.mirutime.app.features.book.ui

import androidx.compose.runtime.mutableStateMapOf
import dev.mirutime.app.features.book.domain.BookDetails

// Last loaded page snapshots (four books). The shell header reads the subtitle
// from here so the title block is correct before the screen reloads, and the
// Compose state map makes it update as soon as the page lands.
internal object BookPageSnapshotStore {
    private const val MaxEntries = 4
    private val entries = mutableStateMapOf<String, BookDetails>()

    fun get(bookUrl: String): BookDetails? = entries[bookUrl]

    fun put(bookUrl: String, book: BookDetails) {
        entries.remove(bookUrl)
        entries[bookUrl] = book
        while (entries.size > MaxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    // Header fallback before the page resolves: never show the boilerplate
    // "Details, chapters and actions" when a real author or source is known.
    fun summaryFor(bookUrl: String): String? {
        val book = entries[bookUrl] ?: return null
        return book.subtitle
            ?: book.author.trim().takeIf { it.isNotEmpty() && !it.equals("Unknown", true) }
            ?: book.source.trim().takeIf { it.isNotEmpty() }
    }
}
