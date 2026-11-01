package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSplitter

enum class TtsTransportAction { Play, Pause, Stop }

/**
 * Intent actions owned by the playback service. Kept here (not in the Android
 * service) so notification/media mapping stays JVM-testable.
 */
object TtsPlaybackIntent {
    const val ActionStart = "dev.mirutime.app.action.TTS_PLAYBACK_START"
    const val ActionPlay = "dev.mirutime.app.action.TTS_PLAYBACK_PLAY"
    const val ActionPause = "dev.mirutime.app.action.TTS_PLAYBACK_PAUSE"
    const val ActionStop = "dev.mirutime.app.action.TTS_PLAYBACK_STOP"
    const val ActionNextChapter = "dev.mirutime.app.action.TTS_PLAYBACK_NEXT_CHAPTER"
    const val ActionPreviousChapter = "dev.mirutime.app.action.TTS_PLAYBACK_PREVIOUS_CHAPTER"

    const val ExtraBookTitle = "book_title"
    const val ExtraChapterTitle = "chapter_title"
    const val ExtraCoverUrl = "cover_url"
    const val ExtraChapterNumber = "chapter_number"
    const val ExtraChapterCount = "chapter_count"
}

/** Service command decoded from an intent action; null means "start". */
enum class TtsTransportCommand { Play, Pause, Stop, NextChapter, PreviousChapter }

fun ttsTransportCommand(action: String?): TtsTransportCommand? = when (action) {
    TtsPlaybackIntent.ActionPlay -> TtsTransportCommand.Play
    TtsPlaybackIntent.ActionPause -> TtsTransportCommand.Pause
    TtsPlaybackIntent.ActionStop -> TtsTransportCommand.Stop
    TtsPlaybackIntent.ActionNextChapter -> TtsTransportCommand.NextChapter
    TtsPlaybackIntent.ActionPreviousChapter -> TtsTransportCommand.PreviousChapter
    else -> null
}

/** Pure mapping of the controller state to the playback notification. */
data class TtsNotificationInfo(
    /** Chapter is the "track": it is the most useful lock-screen title. */
    val title: String,
    /** Book is the "artist": the chapter belongs to it. */
    val text: String,
    val action: TtsTransportAction,
    /** Previous/next chapter actions are meaningful only in a live session. */
    val canSkip: Boolean = false,
)

fun ttsNotificationInfo(
    state: TtsState,
    bookTitle: String,
    chapterTitle: String,
): TtsNotificationInfo {
    val book = bookTitle.trim()
    val chapter = chapterTitle.trim()
    val action = when (state.status) {
        TtsStatus.Playing, TtsStatus.Loading -> TtsTransportAction.Pause
        TtsStatus.Paused -> TtsTransportAction.Play
        TtsStatus.Idle, TtsStatus.Failed -> TtsTransportAction.Stop
    }
    return TtsNotificationInfo(
        title = chapter.ifEmpty { book },
        text = book.ifEmpty { chapter },
        action = action,
        canSkip = state.status != TtsStatus.Idle && state.status != TtsStatus.Failed,
    )
}

/** Percent of the spoken units already reached; 0 when the count is unknown. */
fun ttsProgressPercent(state: TtsState): Int {
    if (state.chunkCount <= 0) return 0
    return (((state.currentChunk + 1f) / state.chunkCount) * 100f)
        .toInt()
        .coerceIn(0, 100)
}

/**
 * Preview text plus the live spoken range mapped onto it. [start] and [end]
 * are offsets inside [text] (which is trimmed), so the floating player can
 * highlight the spoken words without knowing the block layout.
 */
data class TtsSentencePreviewInfo(
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * Text the player shows for the current cursor. The document only stores
 * translation spans for the original, so translated chunks take the rendered
 * translation from the caller and split it the same way the chunker does.
 * Null means the cursor is not on a loaded block yet (chapter hand-off,
 * restore still pending), and the UI should say so rather than invent text.
 */
fun ttsSentencePreview(
    document: ReaderDocument?,
    state: TtsState,
    translatedText: String? = null,
): String? = ttsSentencePreviewInfo(document, state, translatedText)?.text

fun ttsSentencePreviewInfo(
    document: ReaderDocument?,
    state: TtsState,
    translatedText: String? = null,
): TtsSentencePreviewInfo? {
    val blockId = state.blockId ?: return null
    val block = document?.blockAt(blockId) ?: return null
    val source = if (state.translated) translatedText else block.original
    if (source.isNullOrBlank()) return null
    val span = if (state.translated) {
        val spans = SentenceSplitter.spans(source)
        spans.getOrNull(state.sentenceIndex)
            ?: spans.lastOrNull { state.rangeStart in it.start..it.end }
    } else {
        block.sentences.getOrNull(state.sentenceIndex)
    }
    val start = (span?.start ?: state.rangeStart).coerceIn(0, source.length)
    val end = (span?.end ?: state.rangeEnd).coerceIn(start, source.length)
    val raw = source.substring(start, end)
    val first = raw.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return null
    val last = raw.indexOfLast { !it.isWhitespace() }
    val text = raw.substring(first, last + 1)
    val rangeStart = (state.rangeStart - start - first).coerceIn(0, text.length)
    val rangeEnd = (state.rangeEnd - start - first).coerceIn(rangeStart, text.length)
    return TtsSentencePreviewInfo(text = text, start = rangeStart, end = rangeEnd)
}
