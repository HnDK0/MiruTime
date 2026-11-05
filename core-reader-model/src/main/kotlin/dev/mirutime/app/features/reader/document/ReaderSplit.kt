package dev.mirutime.app.features.reader.document

/**
 * Single decision point for sentence splitting, shared by the feed and paged
 * renderers. Splitting is presentation-only: [ReaderBlock.sentences] is the
 * parse-time splitter output and stays valid regardless of the setting.
 */
object ReaderSplit {
    fun shouldSplit(block: ReaderBlock, enabled: Boolean): Boolean =
        enabled && block.kind == BlockKind.Paragraph

    /** Parse-time spans, with a fallback for documents built outside the parser. */
    fun originalSpans(block: ReaderBlock): List<SentenceSpan> =
        block.sentences.ifEmpty { SentenceSplitter.spans(block.original) }
}
