package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.app.features.reader.document.SpeechText
import dev.mirutime.core.log.MiruLog

/** One spoken unit. Offsets refer to the text actually spoken. */
data class TtsChunk(
    val blockId: BlockId,
    val sentenceIndex: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val translated: Boolean,
)

/**
 * Turns a parsed chapter into stable spoken chunks. Translation is used only
 * for blocks that actually have a translated string; images/dividers are
 * skipped. Blocks that carry nothing but decoration, litRPG status panels or
 * chat logs are not spoken at all.
 *
 * Spoken text is [SpeechText.mask]ed (same length), so offsets still address
 * the real characters of the source string and the highlight never drifts.
 * Sentence indices are display-order indices, not character offsets.
 */
object TtsChunker {
    private const val MAX_CHARS = 3_800 // TextToSpeech max input is ~4000

    fun chunks(document: ReaderDocument, translations: Map<Int, String> = emptyMap()): List<TtsChunk> {
        val out = mutableListOf<TtsChunk>()
        for (block in document.blocks) {
            if (block.kind != BlockKind.Heading && block.kind != BlockKind.Paragraph) continue
            val translated = translations[block.id.index]?.takeIf { it.isNotBlank() }
            val text = translated ?: block.original
            SpeechText.noiseReason(text)?.let { reason ->
                MiruLog.event("tts", "skip", "reason" to reason.name, "block" to block.id.index)
                continue
            }
            val spoken = SpeechText.mask(text)
            val spans = if (translated == null && block.sentences.isNotEmpty()) {
                block.sentences
            } else {
                SentenceSplitter.spans(text)
            }
            spans.forEachIndexed { index, span ->
                val start = span.start.coerceIn(0, spoken.length)
                val end = span.end.coerceIn(start, spoken.length)
                val first = firstNonSpace(spoken, start, end)
                if (first < 0) return@forEachIndexed
                val last = lastNonSpace(spoken, start, end)
                splitLong(block.id, index, spoken, first, last + 1, translated != null)
                    .forEach { out += it }
            }
        }
        return out
    }

    private fun splitLong(
        blockId: BlockId,
        sentenceIndex: Int,
        source: String,
        start: Int,
        end: Int,
        translated: Boolean,
    ): List<TtsChunk> {
        if (end - start <= MAX_CHARS) {
            return listOf(
                TtsChunk(blockId, sentenceIndex, source.substring(start, end), start, end, translated),
            )
        }
        val result = mutableListOf<TtsChunk>()
        var cursor = start
        var part = 0
        while (cursor < end) {
            val hardEnd = minOf(cursor + MAX_CHARS, end)
            val split = findSplit(source, cursor, hardEnd)
            val actualEnd = if (split <= cursor) hardEnd else split
            val partStart = firstNonSpace(source, cursor, actualEnd)
            if (partStart >= 0) {
                val partEnd = lastNonSpace(source, cursor, actualEnd) + 1
                result += TtsChunk(
                    blockId,
                    sentenceIndex + part,
                    source.substring(partStart, partEnd),
                    partStart,
                    partEnd,
                    translated,
                )
                part++
            }
            cursor = actualEnd
        }
        return result
    }

    private fun firstNonSpace(text: String, from: Int, to: Int): Int {
        var index = from
        while (index < to && text[index].isWhitespace()) index++
        return if (index < to) index else -1
    }

    private fun lastNonSpace(text: String, from: Int, to: Int): Int {
        var index = to - 1
        while (index >= from && text[index].isWhitespace()) index--
        return index
    }

    private fun findSplit(text: String, from: Int, to: Int): Int {
        for (i in to downTo from + 1) {
            if (i >= text.length) continue
            if (text[i - 1].isWhitespace() || text[i].isWhitespace()) return i
        }
        return to
    }
}
