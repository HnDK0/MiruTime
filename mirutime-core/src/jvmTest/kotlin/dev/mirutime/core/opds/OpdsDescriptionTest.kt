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
}
