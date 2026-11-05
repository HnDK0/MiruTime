package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePairsTest {

    private fun piece(text: String, span: SentenceSpan): String =
        text.substring(span.start, span.end)

    private val originalSentences = listOf(
        "The first sentence is long enough to be real.",
        "The second sentence is also quite long.",
        "The third sentence closes the paragraph.",
    )
    private val translatedSentences = listOf(
        "Первое предложение достаточно длинное.",
        "Второе предложение тоже довольно длинное.",
        "Третье предложение завершает абзац.",
    )

    private val original3 = originalSentences.joinToString(" ")
    private val translated3 = translatedSentences.joinToString(" ")
    private val translated2 = listOf(
        translatedSentences[0],
        "Второе и третье предложения объединены в одно длинное предложение для проверки.",
    ).joinToString(" ")
    private val original2 = listOf(
        originalSentences[0],
        "The second and third sentences are merged into one longer sentence for this test.",
    ).joinToString(" ")

    /** Every non-whitespace char of [source] appears once, in order, in the pairs. */
    private fun assertCovered(
        source: String,
        pairs: List<SentencePair>,
        select: (SentencePair) -> SentenceSpan,
    ) {
        val spans = pairs.map(select)
        assertEquals("first span starts at 0", 0, spans.first().start)
        spans.zipWithNext().forEach { (a, b) ->
            assertTrue("spans stay ordered and non-overlapping", b.start >= a.end)
        }
        assertEquals("last span reaches the source end", source.length, spans.last().end)
        assertEquals(
            "no content is lost or duplicated",
            source.filterNot { it.isWhitespace() },
            spans.joinToString("") { piece(source, it) }.filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun `equal counts pair one to one`() {
        assertEquals(3, SentenceSplitter.spans(original3).size)
        assertEquals(3, SentenceSplitter.spans(translated3).size)

        val pairs = SentencePairs.pair(original3, translated3)

        assertEquals(3, pairs.size)
        pairs.forEachIndexed { i, pair ->
            assertEquals(originalSentences[i], piece(original3, pair.original))
            assertEquals(translatedSentences[i], piece(translated3, pair.translated))
        }
        assertCovered(original3, pairs) { it.original }
        assertCovered(translated3, pairs) { it.translated }
    }

    @Test
    fun `more original sentences merge into the last pair`() {
        assertEquals(3, SentenceSplitter.spans(original3).size)
        assertEquals(2, SentenceSplitter.spans(translated2).size)

        val pairs = SentencePairs.pair(original3, translated2)

        assertEquals(2, pairs.size)
        assertEquals(originalSentences[0], piece(original3, pairs[0].original))
        assertEquals(translatedSentences[0], piece(translated2, pairs[0].translated))
        assertEquals(
            "${originalSentences[1]} ${originalSentences[2]}",
            piece(original3, pairs[1].original),
        )
        assertEquals("Второе и третье предложения объединены в одно длинное предложение для проверки.", piece(translated2, pairs[1].translated))
        assertCovered(original3, pairs) { it.original }
        assertCovered(translated2, pairs) { it.translated }
    }

    @Test
    fun `more translated sentences merge into the last pair`() {
        assertEquals(2, SentenceSplitter.spans(original2).size)
        assertEquals(3, SentenceSplitter.spans(translated3).size)

        val pairs = SentencePairs.pair(original2, translated3)

        assertEquals(2, pairs.size)
        assertEquals(originalSentences[0], piece(original2, pairs[0].original))
        assertEquals(translatedSentences[0], piece(translated3, pairs[0].translated))
        assertEquals(
            "${translatedSentences[1]} ${translatedSentences[2]}",
            piece(translated3, pairs[1].translated),
        )
        assertCovered(original2, pairs) { it.original }
        assertCovered(translated3, pairs) { it.translated }
    }

    @Test
    fun `uses provided original spans`() {
        val text = "Alpha beta. Gamma delta. Epsilon zeta."
        val custom = listOf(SentenceSpan(0, 10), SentenceSpan(11, text.length))

        val pairs = SentencePairs.pair(custom, text)

        assertEquals(2, pairs.size)
        assertEquals(custom[0], pairs[0].original)
        assertEquals(custom[1], pairs[1].original)
        assertCovered(text, pairs) { it.translated }
    }

    @Test
    fun `one short sentence pairs whole`() {
        val original = "Hello."
        val translated = "Привет."

        val pairs = SentencePairs.pair(original, translated)

        assertEquals(1, pairs.size)
        assertEquals(SentenceSpan(0, original.length), pairs[0].original)
        assertEquals(SentenceSpan(0, translated.length), pairs[0].translated)
    }

    @Test
    fun `empty translated yields no pairs`() {
        assertTrue(SentencePairs.pair(original3, "").isEmpty())
    }

    @Test
    fun `empty original yields no pairs`() {
        assertTrue(SentencePairs.pair("", translated3).isEmpty())
        assertTrue(SentencePairs.pair("", "").isEmpty())
    }
}
