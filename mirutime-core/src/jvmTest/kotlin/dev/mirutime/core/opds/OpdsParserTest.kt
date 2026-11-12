package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpdsParserTest {

    private fun parse(xml: String, base: String = "https://flibusta.is/opds/new/0/new") =
        OpdsParser.parse(xml.toByteArray(), base)

    private fun ok(feed: Outcome<OpdsFeed>): OpdsFeed = when (feed) {
        is Outcome.Ok -> feed.value
        is Outcome.Err -> kotlin.test.fail("expected a feed, got ${feed.error}")
    }

    @Test
    fun `flibusta shaped feed has no opds profile and legacy covers`() {
        val feed = ok(
            parse(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
                  <title>Flibusta</title>
                  <id>tag:flibusta,2026:opds</id>
                  <link rel="self" href="/opds" type="application/atom+xml"/>
                  <link rel="search" href="/opds/search?searchTerm={searchTerms}"/>
                  <link rel="next" href="/opds/new/1/new"/>
                  <entry>
                    <title>Война и мир</title>
                    <id>tag:book:1</id>
                    <dc:creator>Толстой, Лев</dc:creator>
                    <summary>Роман-эпопея</summary>
                    <link rel="http://opds-spec.org/thumbnail" href="/opds/cover/1" type="image/jpeg"/>
                    <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/b/1/fb2/opds" type="application/fb2+zip"/>
                    <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/b/1/epub/opds" type="application/epub+zip"/>
                    <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/b/1/pdf/opds" type="application/pdf"/>
                  </entry>
                  <entry>
                    <title>Скан</title>
                    <link rel="http://opds-spec.org/acquisition/open-access" href="/opds/b/2/djvu/opds" type="image/vnd.djvu"/>
                  </entry>
                </feed>
                """.trimIndent(),
            ),
        )

        assertEquals("Flibusta", feed.title)
        assertEquals(2, feed.entries.size)
        assertEquals("https://flibusta.is/opds/new/1/new", feed.nextUrl)
        assertEquals(
            "https://flibusta.is/opds/search?searchTerm=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0+%D0%B8+%D0%BC%D0%B8%D1%80",
            feed.searchUrl("война и мир"),
        )

        val war = feed.entries[0]
        assertEquals("Толстой, Лев", war.author)
        assertEquals("https://flibusta.is/opds/cover/1", war.coverUrl)
        assertEquals(3, war.acquisitions.size)
        // EPUB wins over FB2 and PDF.
        assertEquals(OpdsFormat.EPUB, war.bestAcquisition()?.first)

        // Unsupported book stays visible but offers no download.
        assertEquals(1, feed.entries[1].acquisitions.size)
        assertNull(feed.entries[1].bestAcquisition())
    }

    @Test
    fun `format falls back to extension when type lies or is missing`() {
        val feed = ok(
            parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>A</title>
                    <link rel="http://opds-spec.org/acquisition" href="/b/a.fb2.zip" type="application/octet-stream"/>
                  </entry>
                  <entry>
                    <title>B</title>
                    <link rel="http://opds-spec.org/acquisition" href="/b/b.cbz"/>
                  </entry>
                  <entry>
                    <title>C</title>
                    <link rel="http://opds-spec.org/acquisition" href="/b/c.fb2" type="text/fb2+xml"/>
                  </entry>
                  <entry>
                    <title>D</title>
                    <link rel="http://opds-spec.org/acquisition" href="/b/d.epub.zip" type="application/zip"/>
                  </entry>
                </feed>
                """.trimIndent(),
            ),
        )
        assertEquals(OpdsFormat.FB2_ZIP, feed.entries[0].bestAcquisition()?.first)
        assertEquals(OpdsFormat.CBZ, feed.entries[1].bestAcquisition()?.first)
        assertEquals(OpdsFormat.FB2, feed.entries[2].bestAcquisition()?.first)
        assertEquals("https://flibusta.is/b/c.fb2", feed.entries[2].bestAcquisition()?.second?.href)
        assertEquals(OpdsFormat.EPUB, feed.entries[3].bestAcquisition()?.first)
    }

    @Test
    fun `relative and protocol-relative hrefs resolve against the feed url`() {
        val feed = ok(
            parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>A</title>
                    <link rel="http://opds-spec.org/image" href="//cdn.example/x.jpg" type="image/jpeg"/>
                    <link rel="http://opds-spec.org/acquisition" href="../b/7/epub?library_id=main" type="application/epub+zip"/>
                  </entry>
                </feed>
                """.trimIndent(),
                base = "https://host/opds/new/0/new",
            ),
        )
        val entry = feed.entries.single()
        assertEquals("https://host/opds/new/b/7/epub?library_id=main", entry.bestAcquisition()?.second?.href)
        assertEquals("https://cdn.example/x.jpg", entry.coverUrl)
    }

    @Test
    fun `navigation feed is detected and entries without title are dropped`() {
        val feed = ok(
            parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Каталог</title>
                  <entry>
                    <title>Авторы</title>
                    <link rel="subsection" href="/opds/authors" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  </entry>
                  <entry>
                    <link rel="subsection" href="/opds/genres"/>
                  </entry>
                </feed>
                """.trimIndent(),
            ),
        )
        assertTrue(feed.isNavigation)
        assertEquals(1, feed.entries.size)
    }

    @Test
    fun `prefixed atom elements parse the same as default namespace`() {
        val feed = ok(
            parse(
                """
                <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
                  <atom:title>Prefixed</atom:title>
                  <atom:entry>
                    <atom:title>Book</atom:title>
                    <atom:author><atom:name>Author</atom:name></atom:author>
                    <atom:link rel="http://opds-spec.org/acquisition" href="/b/1.epub" type="application/epub+zip"/>
                  </atom:entry>
                </atom:feed>
                """.trimIndent(),
            ),
        )
        assertEquals("Prefixed", feed.title)
        assertEquals("Author", feed.entries.single().author)
        assertEquals("https://flibusta.is/b/1.epub", feed.entries.single().bestAcquisition()?.second?.href)
    }

    @Test
    fun `categories prefer label, fall back to term and drop blanks`() {
        val feed = ok(
            parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>Tagged</title>
                    <category term="fantasy" label="Fantasy"/>
                    <category term="sf"/>
                    <category term="fantasy" label="Fantasy"/>
                    <category term="   " label=" "/>
                    <link rel="http://opds-spec.org/acquisition" href="/b/1.epub" type="application/epub+zip"/>
                  </entry>
                </feed>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Fantasy", "sf"), feed.entries.single().categories)
    }

    @Test
    fun `json catalog html page and broken xml are rejected with typed errors`() {
        val json = OpdsParser.parse("""{"metadata":{"title":"x"}}""".toByteArray(), "https://h/")
        assertEquals(OpdsError.JsonCatalogUnsupported, (json as Outcome.Err).error)

        val html = OpdsParser.parse("<html><body>login</body></html>".toByteArray(), "https://h/")
        assertEquals(OpdsError.NotAFeed, (html as Outcome.Err).error)

        val broken = OpdsParser.parse("<feed><entry>".toByteArray(), "https://h/")
        assertEquals(OpdsError.Malformed, (broken as Outcome.Err).error)
    }
}
