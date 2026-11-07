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

    fun playHandoff(document: ReaderDocument, translations: Map<Int, String>): Boolean
    fun resume()
    fun stop()
}
