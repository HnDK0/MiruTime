package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpdsDescriptionTest {

    private fun entry(xml: String): OpdsEntry {
        val feed = OpdsParser.parse(xml.trimIndent().toByteArray(), "https://host/opds")
        return (feed as Outcome.Ok).value.entries.single()
    }

    @Test
    fun `escaped html annotation is unescaped and stripped`() {
        val parsed = entry(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Book</title>
                <author><name>Автор</name></author>
                <content type="text/html">&lt;p class=&quot;book&quot;&gt;Первая часть словаря.&lt;br/&gt;Второй абзац.&lt;/p&gt;</content>
              </entry>
            </feed>
            """,
        )
        assertEquals("Первая часть словаря.\nВторой абзац.", parsed.summary)
    }

    @Test
    fun `trailing catalogue metadata is cut from the annotation`() {
        val parsed = entry(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Book</title>
                <content type="text/html">&lt;p&gt;Настоящая аннотация книги.&lt;/p&gt;&lt;p&gt;Ещё абзац.&lt;/p&gt;Год издания: 2026&lt;br/&gt;Формат: fb2&lt;br/&gt;Язык: ru&lt;br/&gt;Размер: 1035 Kb&lt;br/&gt;Серия: Ровнин #5</content>
              </entry>
            </feed>
            """,
        )
        assertEquals("Настоящая аннотация книги.\nЕщё абзац.", parsed.summary)
    }

    @Test
    fun `metadata only body is dropped`() {
        val parsed = entry(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Book</title>
                <content type="text/html">Формат: fb2&lt;br/&gt;Язык: ru&lt;br/&gt;Размер: 587 Kb&lt;br/&gt;</content>
              </entry>
            </feed>
            """,
        )
        assertNull(parsed.summary)
    }

    @Test
    fun `content that repeats the author is not a synopsis`() {
        val parsed = entry(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Pride and Prejudice</title>
                <author><name>Jane Austen</name></author>
                <content type="text">Jane Austen</content>
              </entry>
            </feed>
            """,
        )
        assertNull(parsed.summary)
    }

    @Test
    fun `summary wins over content`() {
        val parsed = entry(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Book</title>
                <summary>Настоящая аннотация.</summary>
                <content type="text/html">Формат: fb2</content>
              </entry>
            </feed>
            """,
        )
        assertEquals("Настоящая аннотация.", parsed.summary)
    }

    @Test
    fun `gutenberg label block is dropped after real prose`() {
        val cleaned = OpdsParser.cleanDescription(
            """
            A novel of manners and marriage.

            Title: Pride and Prejudice
            Author: Austen, Jane
            Credits: Produced by John Doe and the Online Distributed Proofreading Team
            Summary: The story of the Bennet family
            Subject: Courtship -- Fiction
            Category: Classics
            Reading Level: General
            Downloads: 12345
            Ebook No.: 1342
            LoCC: PR4034
            Note: see the film adaptation
            Alternate Title: First Impressions
            Publisher: Penguin
            Language: English
            """.trimIndent(),
        )

        assertEquals("A novel of manners and marriage.", cleaned)
    }

    @Test
    fun `library collation is dropped and the quoted prose kept`() {
        val cleaned = OpdsParser.cleanDescription(
            "xiv, 276 p. : 22 cm\n\"A passage quoted from the original.\"",
        )

        assertEquals("\"A passage quoted from the original.\"", cleaned)
    }

    @Test
    fun `a sentence that opens with a label is prose, not metadata`() {
        val sentence = "Note: this edition keeps the original spelling and punctuation."
        assertEquals(sentence, OpdsParser.cleanDescription(sentence))

        // Unpunctuated and long: a paragraph, however much it looks like a label.
        val paragraph = "Note: " + "lorem ipsum ".repeat(12)
        assertEquals(paragraph.trim(), OpdsParser.cleanDescription(paragraph))
    }

    @Test
    fun `an annotation of nothing but labels is dropped`() {
        assertNull(OpdsParser.cleanDescription("Title: X\nAuthor: Y\nLanguage: English"))
        assertNull(OpdsParser.cleanDescription("xiv, 276 p. : 22 cm"))
    }
}
