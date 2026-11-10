package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.translation.data.ChapterTranslationConfig
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.domain.TranslationRequest

/**
 * One forced re-translation of the active chapter.
 *
 * The request is built from the document currently on screen, so stored block
 * indices always match what the reader paints. The controller keeps the old
 * translation visible while the new attempt runs and restores it on failure.
 *
 * Only one re-translation per chapter runs at a time; a second tap returns
 * true instead of queueing a duplicate request.
 */
internal class ChapterRetranslator(
    private val currentDocument: (bookUrl: String, chapterIndex: Int) -> ReaderDocument?,
    private val config: (bookUrl: String) -> ChapterTranslationConfig?,
    private val submit: suspend (TranslationRequest) -> Boolean,
) {
    private val lock = Any()
    private val running = mutableSetOf<String>()

    suspend fun retranslate(bookUrl: String, chapterIndex: Int): Boolean {
        val key = key(bookUrl, chapterIndex)
        synchronized(lock) {
            if (key in running) return true
            running += key
        }
        try {
            val document = currentDocument(bookUrl, chapterIndex)
                ?.takeIf { !it.translationOnly }
                ?: return false
            val chapterConfig = config(bookUrl) ?: return false
            val request = ChapterTranslationPlanner.request(document, chapterConfig)
                ?: return false
            return submit(request)
        } finally {
            synchronized(lock) { running -= key }
        }
    }

    private fun key(bookUrl: String, chapterIndex: Int): String = "$bookUrl|$chapterIndex"
}
