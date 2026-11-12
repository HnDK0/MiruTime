package dev.mirutime.feature.tts.api

import androidx.compose.runtime.Immutable
import dev.mirutime.app.features.reader.document.BlockId

/** Pure TTS highlight state shared by the reader and the TTS UI. */
@Immutable
data class TtsHighlight(
    val blockId: BlockId,
    val start: Int,
    val end: Int,
    val colorArgb: Long,
    /** TTS is speaking the translated string, so only that copy gets a range. */
    val translated: Boolean = false,
    /** Sentence/word range is optional; the active block is always marked. */
    val rangeEnabled: Boolean = true,
)
