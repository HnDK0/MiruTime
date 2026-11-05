package dev.mirutime.app.features.reader.document

/**
 * One original sentence paired with the translated sentence that renders it.
 * Offsets are relative to their own source string, so callers keep the two
 * strings and slice them independently.
 */
data class SentencePair(
    val original: SentenceSpan,
    val translated: SentenceSpan,
)

/**
 * Aligns original and translated sentences using the single [SentenceSplitter].
 *
 * Equal counts are zipped one-to-one. When counts differ, the leading
 * [min] sentences are paired and the last pair absorbs every remaining span
 * on the longer side, so no non-whitespace text is dropped. A blank side
 * yields no pairs: [pair] returns an empty list when either string is empty.
 */
object SentencePairs {
    /** Convenience overload for callers that only have the source string. */
    fun pair(original: String, translated: String): List<SentencePair> =
        pair(SentenceSplitter.spans(original), translated)

    /**
     * Pairs precomputed original spans (for example [ReaderBlock.sentences])
     * with translated spans, so the original is never re-split at render time.
     */
    fun pair(originalSpans: List<SentenceSpan>, translated: String): List<SentencePair> {
        val translatedSpans = SentenceSplitter.spans(translated)
        if (originalSpans.isEmpty() || translatedSpans.isEmpty()) return emptyList()

        val paired = minOf(originalSpans.size, translatedSpans.size)
        val result = ArrayList<SentencePair>(paired)
        for (i in 0 until paired - 1) {
            result += SentencePair(originalSpans[i], translatedSpans[i])
        }
        val lastOriginal = originalSpans[paired - 1]
        val lastTranslated = translatedSpans[paired - 1]
        result += SentencePair(
            SentenceSpan(lastOriginal.start, maxOf(lastOriginal.end, originalSpans.last().end)),
            SentenceSpan(lastTranslated.start, maxOf(lastTranslated.end, translatedSpans.last().end)),
        )
        return result
    }
}
