package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display-only paragraph grouping. Spans stay sentence-level; these tests only
 * cover which spans are painted as one visual paragraph.
 */
class ReaderDisplayTest {

    private fun groups(text: String, splitEnabled: Boolean = true): List<DisplayGroup> =
        ReaderDisplay.groups(text, SentenceSplitter.spans(text), splitEnabled)

    private fun groupTexts(text: String, splitEnabled: Boolean = true): List<String> =
        groups(text, splitEnabled).map { text.substring(it.start, it.end) }

    private val longNarration =
        "He walked through the quiet streets of the old town, watching the lights flicker on in the windows of the houses he passed one by one. " +
            "The sun was setting slowly behind the distant hills, painting the whole sky in warm shades of orange and pink. " +
            "Birds sang their evening songs from the treetops, filling the quiet air with gentle and soothing melodies."

    @Test
    fun `split off keeps one group`() {
        val text = longNarration
        val result = groups(text, splitEnabled = false)

        assertEquals(1, result.size)
        assertEquals(DisplayGroup(0, text.length, SentenceSplitter.spans(text)), result.single())
    }

    @Test
    fun `short non quote paragraph splits per sentence`() {
        val text = "He walked home. The sun was setting."
        val spans = SentenceSplitter.spans(text)
        val result = groups(text)

        assertEquals(spans.size, result.size)
        assertEquals(spans, result.flatMap { it.spans })
    }

    @Test
    fun `long narration keeps one group per sentence`() {
        val spans = SentenceSplitter.spans(longNarration)
        assertTrue(spans.size >= 3)

        val result = groups(longNarration)

        assertEquals(spans.size, result.size)
        assertEquals(spans, result.flatMap { it.spans })
    }

    @Test
    fun `whole quoted paragraph is one group`() {
        val text = "\"Junior Brothers and Sisters, you must cross the Daluo Immortal Bridge every morning to attend the lectures given by the Elders, and you must remember this rule well and never forget it. Otherwise, unless there is a truly valid reason, missing the morning class will result in a serious punishment for the whole group of newly inducted disciples.\""

        assertEquals(1, groups(text).size)
    }

    @Test
    fun `quoted paragraph with trailing period stays one group`() {
        val text = "«Большинство из нас не встретит Потустороннего за всю свою жизнь. Это заставило церкви заподозрить то же самое. Хотя это и так, я уверен, что они всё ещё существуют»."

        assertEquals(1, groups(text).size)
    }

    @Test
    fun `inline quote is not split inside in long narration`() {
        val text = "The guide spoke for a long time about the old rules of the road and the many dangers that waited beyond the gate. " +
            "He said \"Just wait right here and do not move. Things will be fine.\" " +
            "Then he walked away down the long corridor without looking back even once, disappearing around the corner where the light was dim."

        val result = groups(text)

        assertEquals(SentenceSplitter.spans(text), result.flatMap { it.spans })
        assertTrue(result.any { it.spans.size == 1 })
        assertEquals(SentenceSplitter.spans(text).size, result.size)
    }

    @Test
    fun `punctuation only span merges into previous group`() {
        val text = "First sentence that is definitely long enough to make this paragraph exceed the threshold value. " +
            ". " +
            "Second sentence also long enough to keep the paragraph well over the threshold limit."

        val spans = SentenceSplitter.spans(text)
        val punctuation = spans.indexOfFirst { text.substring(it.start, it.end) == "." }
        assertTrue("test text must contain a punctuation-only span", punctuation > 0)

        val result = groups(text)

        assertEquals(spans.size - 1, result.size)
        assertTrue(result.any { it.spans.size >= 2 })
        assertEquals(spans, result.flatMap { it.spans })
        assertTrue(result.none { text.substring(it.start, it.end) == "." })
    }

    @Test
    fun `punctuation only pair merges into previous pair`() {
        val text = "AAAA. . BBBB."
        val pairs = listOf(
            SentencePair(SentenceSpan(0, 5), SentenceSpan(0, 5)),
            SentencePair(SentenceSpan(6, 7), SentenceSpan(6, 7)),
            SentencePair(SentenceSpan(8, 13), SentenceSpan(8, 13)),
        )

        val merged = ReaderDisplay.mergePunctuationPairs(text, pairs)

        assertEquals(2, merged.size)
        assertEquals(SentenceSpan(0, 7), merged[0].original)
        assertEquals(SentenceSpan(0, 7), merged[0].translated)
        assertEquals(SentenceSpan(8, 13), merged[1].original)
    }

    @Test
    fun `leading punctuation pair stays its own pair`() {
        val text = ". AAAA. BBBB."
        val pairs = listOf(
            SentencePair(SentenceSpan(0, 1), SentenceSpan(0, 1)),
            SentencePair(SentenceSpan(2, 7), SentenceSpan(2, 7)),
            SentencePair(SentenceSpan(8, 13), SentenceSpan(8, 13)),
        )

        val merged = ReaderDisplay.mergePunctuationPairs(text, pairs)

        assertEquals(3, merged.size)
        assertEquals(SentenceSpan(0, 1), merged[0].original)
    }

    @Test
    fun `closing quote only fragment merges into previous group`() {
        val prefix = "First long sentence that passes the threshold value here. "
        val quote = "»"
        val suffix = " Second long sentence that keeps the paragraph long enough to pass the display threshold."
        val text = prefix + quote + suffix
        val spans = listOf(
            SentenceSpan(0, prefix.trimEnd().length),
            SentenceSpan(prefix.length, prefix.length + quote.length),
            SentenceSpan(prefix.length + quote.length + 1, text.length),
        )

        val result = ReaderDisplay.groups(text, spans, splitEnabled = true)

        assertEquals(2, result.size)
        assertTrue(result.none { text.substring(it.start, it.end) == quote })
    }

    @Test
    fun `leading punctuation stays its own group`() {
        val text = ". " + "First sentence that is definitely long enough to make this paragraph exceed the threshold value. " +
            "Second sentence also long enough to keep the paragraph well over the threshold limit."

        val result = groups(text)

        assertEquals(".", text.substring(result.first().start, result.first().end))
    }

    @Test
    fun `structural blocks stay one group`() {
        val status = "Имя: Джон\nУровень: 5\nHP: 120/120\nMP: 100/100\nСила: 15\nЛовкость: 12"
        val log = "[12:34] Alice: Are you there? Hello? It's me, the one who called you yesterday."
        val dash = "— Привет, как дела? — спросил он, подходя ближе и протягивая руку. — Хорошо, спасибо, — ответила она и улыбнулась, глядя ему в глаза."
        val frame = "╔══════════════════════════════════════════════════════╗\n║ A very long framed message that should stay untouched ║\n╚══════════════════════════════════════════════════════╝"

        listOf(status, log, dash, frame).forEach { text ->
            assertEquals("text=$text", 1, groups(text).size)
        }
    }

    @Test
    fun `out of bounds spans are clamped instead of crashing`() {
        val text = longNarration
        val spans = listOf(
            SentenceSpan(0, 10),
            SentenceSpan(10, text.length + 50),
        )

        val result = ReaderDisplay.groups(text, spans, splitEnabled = true)

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.start >= 0 && it.end <= text.length })
        assertTrue(result.flatMap { it.spans }.all { it.start >= 0 && it.end <= text.length })
    }

    @Test
    fun `unclosed quote is not treated as a quote wrapped paragraph`() {
        assertFalse(SentenceSplitter.isQuoteWrappedParagraph("\"never closed. Another sentence."))
        assertEquals(
            1,
            ReaderDisplay.groups(
                "\"never closed. Another sentence.",
                SentenceSplitter.spans("\"never closed. Another sentence."),
                splitEnabled = true,
            ).size,
        )
    }

    @Test
    fun `group starts match group starts`() {
        val result = groups(longNarration)
        assertEquals(result.map { it.start }.toSet(), ReaderDisplay.groupStarts(result))
    }

    @Test
    fun `groups are ordered non overlapping and keep every span once`() {
        val text = longNarration
        val spans = SentenceSplitter.spans(text)
        val result = groups(text)

        var previousEnd = 0
        result.forEach { group ->
            assertTrue(group.start >= previousEnd)
            assertTrue(group.end > group.start)
            assertTrue(group.end <= text.length)
            previousEnd = group.end
        }
        assertEquals(spans, result.flatMap { it.spans })
    }
}
