package dev.mirutime.feature.reader

class ReaderProgressStore {
    private val byBook = mutableMapOf<String, ReaderProgress>()

    fun load(bookUrl: String): ReaderProgress? = byBook[bookUrl]

    fun save(bookUrl: String, progress: ReaderProgress) {
        byBook[bookUrl] = progress
    }

    fun clear(bookUrl: String) {
        byBook.remove(bookUrl)
    }

    companion object {
        /** In-memory until the data lib owns progress persistence. */
        val Default = ReaderProgressStore()
    }
}

fun progressPercent(itemIndex: Int, itemCount: Int): Float =
    if (itemCount <= 0) 0f else ((itemIndex + 1).toFloat() / itemCount * 100f).coerceIn(0f, 100f)

/** Index to restore after chapter load; a changed chapter or split mode starts at the top. */
fun restoreItemIndex(
    progress: ReaderProgress?,
    chapterIndex: Int,
    splitSentences: Boolean,
    itemCount: Int,
): Int {
    if (progress == null || progress.chapterIndex != chapterIndex || progress.splitSentences != splitSentences) {
        return 0
    }
    return progress.itemIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
}
