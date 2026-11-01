package dev.mirutime.core.reader.html

/**
 * Intermediate parser output. This is deliberately not [dev.mirutime.app.features.reader.document.ReaderBlock]:
 * the rich document block carries ids, spans, sentences and image refs.
 */
sealed interface ParsedBlock {
    data class Heading(val text: String) : ParsedBlock
    data class Paragraph(val text: String) : ParsedBlock
}
