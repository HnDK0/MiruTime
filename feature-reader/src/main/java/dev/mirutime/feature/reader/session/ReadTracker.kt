package dev.mirutime.feature.reader.session

import dev.mirutime.feature.reader.api.ReaderContentSource

class ReadTracker(private val content: ReaderContentSource) {
    private val marked = mutableSetOf<String>()

    suspend fun markFinished(bookUrl: String, chapterIndex: Int) {
        if (marked.add("$bookUrl#$chapterIndex")) {
            content.markRead(bookUrl, chapterIndex, true)
        }
    }
}
