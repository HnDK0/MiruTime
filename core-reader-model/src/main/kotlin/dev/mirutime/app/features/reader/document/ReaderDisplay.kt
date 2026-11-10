package dev.mirutime.app.features.reader.document

/** One visual paragraph: a range in the text plus the sentence spans it contains. */
data class DisplayGroup(
    val start: Int,
    val end: Int,
    val spans: List<SentenceSpan>,
)

/**
 * Display-only grouping of sentence spans into visual paragraphs.
 *
 * Sentence spans stay untouched (TTS, highlighting and parallel pairs keep
 * working on them). Only the number of visible paragraphs changes: quoted
 * paragraphs and punctuation-only fragments are kept together.
 */
object ReaderDisplay {
    fun groups(text: String, spans: List<SentenceSpan>, splitEnabled: Boolean): List<DisplayGroup> {
        val clamped = spans.mapNotNull { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end > start) SentenceSpan(start, end) else null
        }
        val whole = listOf(DisplayGroup(0, text.length, clamped))
        if (!splitEnabled || clamped.size <= 1) return whole
        if (SentenceSplitter.isQuoteWrappedParagraph(text)) return whole

        val result = mutableListOf<DisplayGroup>()
        for (span in clamped) {
            val fragment = text.substring(span.start, span.end)
            val last = result.lastOrNull()
            if (last != null && TextStructure.isPunctuationOnly(fragment)) {
                result[result.lastIndex] = last.copy(
                    end = maxOf(last.end, span.end),
                    spans = last.spans + span,
                )
            } else {
                result += DisplayGroup(span.start, span.end, listOf(span))
            }
        }
        return result.ifEmpty { whole }
    }

    fun groupsForBlock(block: ReaderBlock, splitEnabled: Boolean): List<DisplayGroup> =
        groups(block.original, ReaderSplit.originalSpans(block), splitEnabled)

    fun groupsForText(text: String, spans: List<SentenceSpan>, splitEnabled: Boolean): List<DisplayGroup> =
        groups(text, spans, splitEnabled)

    fun groupStarts(groups: List<DisplayGroup>): Set<Int> = groups.map { it.start }.toSet()

    /**
     * Display-only merge of a punctuation-only pair into the previous pair.
     * Pair identity for TTS/translation is not changed anywhere else; this only
     * keeps a stray "." from becoming its own painted line in parallel mode.
     */
    fun mergePunctuationPairs(text: String, pairs: List<SentencePair>): List<SentencePair> {
        val result = mutableListOf<SentencePair>()
        for (pair in pairs) {
            val start = pair.original.start.coerceIn(0, text.length)
            val end = pair.original.end.coerceIn(start, text.length)
            val fragment = text.substring(start, end)
            val last = result.lastOrNull()
            if (last != null && TextStructure.isPunctuationOnly(fragment)) {
                result[result.lastIndex] = SentencePair(
                    original = SentenceSpan(last.original.start, maxOf(last.original.end, end)),
                    translated = SentenceSpan(last.translated.start, maxOf(last.translated.end, pair.translated.end)),
                )
            } else {
                result += pair
            }
        }
        return result
    }
}
