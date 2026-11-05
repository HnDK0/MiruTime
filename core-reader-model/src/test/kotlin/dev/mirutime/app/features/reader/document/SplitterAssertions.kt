package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared invariant checks for the corpus and fuzz splitter tests. */
internal fun assertSpanInvariants(label: String, text: String, spans: List<SentenceSpan>) {
    if (text.isBlank()) {
        assertTrue("$label blank text yields no spans", spans.isEmpty())
        return
    }
    assertTrue("$label non-blank text yields spans", spans.isNotEmpty())
    var previousEnd = 0
    for (span in spans) {
        assertTrue(
            "$label span bounds start=${span.start} end=${span.end} len=${text.length}",
            span.start >= previousEnd && span.start < span.end && span.end <= text.length,
        )
        assertTrue("$label gap is whitespace only", text.substring(previousEnd, span.start).isBlank())
        previousEnd = span.end
    }
    assertTrue("$label tail is whitespace only", text.substring(previousEnd).isBlank())
    assertEquals(
        "$label no content lost",
        text.filterNot { it.isWhitespace() },
        spans.joinToString("") { text.substring(it.start, it.end) }.filterNot { it.isWhitespace() },
    )
}
