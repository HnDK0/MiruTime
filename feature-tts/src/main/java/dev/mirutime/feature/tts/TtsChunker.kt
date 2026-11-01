package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.app.features.reader.document.SentenceSpan

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
 * skipped. Sentence indices are display-order indices, not character offsets.
 */
object TtsChunker {
    private const val MAX_CHARS = 3_800 // TextToSpeech max input is ~4000

    fun chunks(document: ReaderDocument, translations: Map<Int, String> = emptyMap()): List<TtsChunk> {
        val out = mutableListOf<TtsChunk>()
        for (block in document.blocks) {
            if (block.kind != BlockKind.Heading && block.kind != BlockKind.Paragraph) continue
            val translated = translations[block.id.index]?.takeIf { it.isNotBlank() }
            val text = translated ?: block.original
            val spans = if (translated == null && block.sentences.isNotEmpty()) {
                block.sentences
            } else {
                SentenceSplitter.spans(text)
            }
            spans.forEachIndexed { index, span ->
                val start = span.start.coerceIn(0, text.length)
                val end = span.end.coerceIn(start, text.length)
                val raw = text.substring(start, end).trim()
                if (raw.isEmpty()) return@forEachIndexed
                val leading = text.substring(start, end).indexOfFirst { !it.isWhitespace() }
                    .coerceAtLeast(0)
                val actualStart = start + leading
                val actualEnd = start + text.substring(start, end).trimEnd().length
                splitLong(block.id, index, text, actualStart, actualEnd, translated != null)
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
                TtsChunk(blockId, sentenceIndex, source.substring(start, end).trim(), start, end, translated),
            )
        }
        val result = mutableListOf<TtsChunk>()
        var cursor = start
        var part = 0
        while (cursor < end) {
            val hardEnd = minOf(cursor + MAX_CHARS, end)
            val split = findSplit(source, cursor, hardEnd)
            val actualEnd = if (split <= cursor) hardEnd else split
            val text = source.substring(cursor, actualEnd).trim()
            if (text.isNotEmpty()) {
                result += TtsChunk(blockId, sentenceIndex + part, text, cursor, actualEnd, translated)
                part++
            }
            cursor = actualEnd
        }
        return result
    }

    private fun findSplit(text: String, from: Int, to: Int): Int {
        for (i in to downTo from + 1) {
            if (i >= text.length) continue
            if (text[i - 1].isWhitespace() || text[i].isWhitespace()) return i
        }
        return to
    }

    /** Same sentence boundaries used for speaking and UI split. */
    fun sentenceSpans(text: String): List<SentenceSpan> = SentenceSplitter.spans(text)
}
