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

    // The loaded page owns the real title once it lands; the shell header must
    // not fall back to a URL slug while the index cache is cold.
    fun titleFor(bookUrl: String): String? =
        entries[bookUrl]?.title?.takeIf { it.isNotBlank() }

    // Header fallback before the page resolves: never show the boilerplate
    // "Details, chapters and actions" when a real author or source is known.
    fun summaryFor(bookUrl: String): String? {
        val book = entries[bookUrl] ?: return null
        return book.subtitle
            ?: book.author.trim().takeIf { it.isNotEmpty() && !it.equals("Unknown", true) }
            ?: book.source.trim().takeIf { it.isNotEmpty() }
    }
}

// Snapshot title wins as soon as the page loads. A blank snapshot keeps the
// cached index title (never null for a book route) so the shell does not fall
// through to the tab label.
internal fun bookHeaderTitle(snapshotTitle: String?, indexTitle: String?): String? =
    snapshotTitle?.takeIf { it.isNotBlank() }
        ?: indexTitle?.takeIf { it.isNotBlank() }
