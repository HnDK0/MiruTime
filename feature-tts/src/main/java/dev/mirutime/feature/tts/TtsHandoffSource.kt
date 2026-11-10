package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.ReaderDocument
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of [TtsController] the reader hand-off coordinator needs. Keeping
 * it narrow lets the coordinator be tested without a platform TTS engine.
 */
interface TtsHandoffSource {
    val state: StateFlow<TtsState>
    val chapterRequests: SharedFlow<TtsChapterDirection>
    val chapterEndedNaturally: Boolean
    val lastStoppedSession: TtsSession?

    /** Language the live session speaks; null when there is no session. */
    val sessionLanguage: TtsLanguage?

    fun playHandoff(
        document: ReaderDocument,
        translations: Map<Int, String>,
        language: TtsLanguage = TtsLanguage.from(translations),
    ): Boolean
    fun resume()
    fun stop()

    /**
     * Re-queues the spoken block in another language, keeping the transport
     * state (a paused session stays paused). Used when the language the page
     * needs changes while speech runs.
     */
    fun switchLanguage(
        document: ReaderDocument,
        translations: Map<Int, String>,
        blockIndex: Int,
        language: TtsLanguage,
    ) {
    }
}
