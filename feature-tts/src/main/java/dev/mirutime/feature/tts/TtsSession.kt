package dev.mirutime.feature.tts

import kotlin.math.abs

/**
 * Persisted TTS cursor. [blockIndex], [sentenceIndex] and [charOffset] identify
 * the spoken unit; [textHash] is only diagnostic and never required to resolve.
 */
data class TtsSession(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val blockIndex: Int,
    val sentenceIndex: Int,
    val charOffset: Int,
    val voiceId: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val wasPlaying: Boolean = false,
    val translated: Boolean = false,
    val textHash: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Finds the chunk to restore. Returns null when the chapter differs or the
 * saved block no longer exists. Re-chunking and translation toggles are
 * tolerated by falling back from the exact offset to block+sentence, then to
 * the nearest offset in the same block.
 */
fun resolveTtsCursor(chunks: List<TtsChunk>, session: TtsSession): Int? {
    if (chunks.isEmpty()) return null
    if (chunks.first().blockId.chapterUrl != session.chapterUrl) return null
    val block = chunks.withIndex().filter { it.value.blockId.index == session.blockIndex }
    if (block.isEmpty()) {
        // The saved block may no longer be spoken (status panel, divider,
        // chat log). Only a later block proves the text still exists; an index
        // past the end is a changed chapter and stays rejected.
        return chunks.withIndex()
            .firstOrNull { it.value.blockId.index > session.blockIndex }
            ?.index
    }
    block.firstOrNull {
        it.value.sentenceIndex == session.sentenceIndex &&
            it.value.startOffset == session.charOffset &&
            it.value.translated == session.translated
    }?.let { return it.index }
    block.firstOrNull {
        it.value.sentenceIndex == session.sentenceIndex &&
            it.value.startOffset == session.charOffset
    }?.let { return it.index }
    block.firstOrNull {
        it.value.sentenceIndex == session.sentenceIndex &&
            it.value.translated == session.translated
    }?.let { return it.index }
    block.firstOrNull { it.value.sentenceIndex == session.sentenceIndex }?.let { return it.index }
    return block.minByOrNull { abs(it.value.startOffset - session.charOffset) }?.index
}

/** Text-only codec so the store and its tests need no Android JSON stubs. */
object TtsSessionCodec {
    private const val VERSION = "1"
    private const val SEPARATOR = '&'
    private const val PAIR = '='

    fun encode(session: TtsSession): String = buildString {
        append("v").append(PAIR).append(VERSION)
        appendField("book", session.bookUrl)
        appendField("chapterIndex", session.chapterIndex.toString())
        appendField("chapter", session.chapterUrl)
        appendField("block", session.blockIndex.toString())
        appendField("sentence", session.sentenceIndex.toString())
        appendField("offset", session.charOffset.toString())
        session.voiceId?.let { appendField("voice", it) }
        appendField("speed", session.speed.toString())
        appendField("pitch", session.pitch.toString())
        appendField("playing", if (session.wasPlaying) "1" else "0")
        appendField("translated", if (session.translated) "1" else "0")
        session.textHash?.let { appendField("hash", it) }
        appendField("updatedAt", session.updatedAt.toString())
    }

    fun decode(raw: String?): TtsSession? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split(SEPARATOR).mapNotNull { part ->
            val at = part.indexOf(PAIR)
            if (at <= 0) null else part.substring(0, at) to unescape(part.substring(at + 1))
        }.toMap()
        if (fields["v"] != VERSION) return null
        val bookUrl = fields["book"].orEmpty()
        val chapterUrl = fields["chapter"].orEmpty()
        if (bookUrl.isBlank() || chapterUrl.isBlank()) return null
        return TtsSession(
            bookUrl = bookUrl,
            chapterIndex = fields["chapterIndex"]?.toIntOrNull() ?: return null,
            chapterUrl = chapterUrl,
            blockIndex = fields["block"]?.toIntOrNull() ?: return null,
            sentenceIndex = fields["sentence"]?.toIntOrNull() ?: return null,
            charOffset = fields["offset"]?.toIntOrNull() ?: return null,
            voiceId = fields["voice"]?.takeIf { it.isNotBlank() },
            speed = fields["speed"]?.toFloatOrNull() ?: 1f,
            pitch = fields["pitch"]?.toFloatOrNull() ?: 1f,
            wasPlaying = fields["playing"] == "1",
            translated = fields["translated"] == "1",
            textHash = fields["hash"]?.takeIf { it.isNotBlank() },
            updatedAt = fields["updatedAt"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(SEPARATOR).append(name).append(PAIR).append(escape(value))
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '%', '&', '=', '\n', '\r' ->
                    append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                else -> append(ch)
            }
        }
    }

    private fun unescape(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    index += 3
                    continue
                }
            }
            out.append(ch)
            index++
        }
        return out.toString()
    }
}
