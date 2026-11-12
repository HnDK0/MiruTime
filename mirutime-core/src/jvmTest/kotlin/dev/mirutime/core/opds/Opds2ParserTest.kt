package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** OPDS 2.0: shapes copied from archive.org (real bodies, truncated). */
class Opds2ParserTest {

    private fun parse(json: String, base: String = "https://archive.org/services/opds"): OpdsFeed =
        when (val feed = Opds2Parser.parse(json.toByteArray(), base)) {
            is Outcome.Ok -> feed.value
            is Outcome.Err -> fail("expected a feed, got ${feed.error}")
        }

    @Test
    fun `archive root maps navigation to sections and keeps the search template`() {
        val feed = parse(
            """
            {"@context":"https://readium.org/webpub-manifest/context.jsonld",
             "metadata":{"title":"Archive.org"},
             "links":[{"href":"https://archive.org/services/opds/catalog{?query}&type=search",
                       "type":"application/opds+json","rel":"search","templated":true},
                      {"href":"https://archive.org/services/opds/catalog?type=navigation&nav_key=main",
                       "type":"application/opds+json","rel":"self"}],
             "navigation":[{"href":"https://archive.org/services/opds/catalog?type=navigation&nav_key=page_ebooks",
                            "title":"eBooks","type":"application/opds+json","rel":"collection"},
                           {"href":"https://archive.org/services/opds/catalog?type=navigation&nav_key=page_audiobooks",
                            "title":"Audiobooks","type":"application/opds+json","rel":"collection"}],
             "groups":[{"metadata":{"title":"eBooks Open Access & Public Domain","numberOfItems":3667231},
                        "links":[{"href":"https://archive.org/services/opds/catalog?type=browse&section=ebooks",
                                  "type":"application/opds+json","rel":"self"}],
                        "publications":[${publicationJson("b30547234_0001")}]}]}
            """.trimIndent(),
        )

        assertEquals("Archive.org", feed.title)
        val sections = feed.entries.filter { !it.isPublication }
        assertEquals(listOf("eBooks", "Audiobooks"), sections.map { it.title })
        assertTrue(sections.all { it.acquisitions.isEmpty() })
        assertEquals("https://archive.org/services/opds/catalog?type=navigation&nav_key=page_ebooks", sections[0].links.single().href)

        // The template survives paging-style href resolution with its braces.
        assertEquals("https://archive.org/services/opds/catalog{?query}&type=search", feed.searchLink?.href)
        assertEquals(
            "https://archive.org/services/opds/catalog?query=a+b&type=search",
            feed.searchUrl("a b"),
        )

        // A group's publications are flattened as books, never as sections.
        val preview = feed.entries.single { it.isPublication }
        assertEquals("A reply to Mr. Maxwell's Answer", preview.title)
        assertNull(feed.nextUrl)
    }

    @Test
    fun `publications page maps metadata, cover and only real acquisition links`() {
        val feed = parse(
            """
            {"@context":"https://readium.org/webpub-manifest/context.jsonld",
             "metadata":{"title":"eBooks Open Access & Public Domain","numberOfItems":10000,
                         "itemsPerPage":25,"currentPage":1},
             "links":[{"href":"https://archive.org/services/opds/catalog{?query}&type=search",
                       "type":"application/opds+json","rel":"search","templated":true},
                      {"href":"https://archive.org/services/opds/catalog?type=browse&section=ebooks&item=open-access&page=2",
                       "type":"application/opds+json","rel":"next"}],
             "publications":[${publicationJson("b30547234_0001")},${publicationJson("second_book")}]}
            """.trimIndent(),
        )

        assertEquals(2, feed.entries.size)
        assertEquals(
            "https://archive.org/services/opds/catalog?type=browse&section=ebooks&item=open-access&page=2",
            feed.nextUrl,
        )

        val book = feed.entries.first()
        assertEquals("A reply to Mr. Maxwell's Answer", book.title)
        assertEquals("Kirkland, Thomas, 1722-1798", book.author)
        assertEquals("2 unnumbered leaves, viii, 107 pages ; (8vo)", book.summary)
        assertEquals("https://archive.org/details/b30547234_0001", book.id)
        assertEquals(
            "https://archive.org/download/b30547234_0001/__ia_thumb.jpg",
            book.coverUrl,
        )
        assertTrue(book.isPublication)

        // Sample page and the Readium manifest are not files; the pdf is the
        // only acquisition, and no reader opens it.
        assertEquals(
            listOf("http://opds-spec.org/acquisition/sample", "http://opds-spec.org/acquisition/open-access"),
            book.acquisitionLinks.map { it.rel },
        )
        assertTrue(book.acquisitionLinks.none { it.type == "application/opds-publication+json" })
        assertNull(book.bestAcquisition())

        // The manifest type is never a format, even when the href looks like one.
        assertNull(
            OpdsMediaTypes.formatOf(
                OpdsLink(
                    rel = "self",
                    href = "https://archive.org/services/loans/loan?action=webpub&identifier=1",
                    type = "application/opds-publication+json",
                    title = null,
                ),
            ),
        )
        assertEquals(
            OpdsFormat.EPUB,
            OpdsMediaTypes.formatOf(
                OpdsLink(
                    rel = "http://opds-spec.org/acquisition/open-access",
                    href = "https://archive.org/download/1/book.epub",
                    type = "application/epub+zip",
                    title = null,
                ),
            ),
        )
    }

    @Test
    fun `facets carry their group title and counts`() {
        val feed = parse(
            """
            {"@context":"https://readium.org/webpub-manifest/context.jsonld",
             "metadata":{"title":"eBooks"},
             "publications":[],
             "facets":[{"metadata":{"title":"Browse by Language"},"links":[
               {"title":"English","href":"https://archive.org/services/opds/catalog?facet_item=english",
                "type":"application/opds+json"},
               {"title":"French","href":"https://archive.org/services/opds/catalog?facet_item=french",
                "type":"application/opds+json","properties":{"numberOfItems":12}}]}]}
            """.trimIndent(),
        )

        val group = feed.facets.single()
        assertEquals("Browse by Language", group.label)
        assertEquals(listOf("English", "French"), group.options.map { it.label })
        assertEquals(listOf<Int?>(null, 12), group.options.map { it.count })
        assertEquals(
            "https://archive.org/services/opds/catalog?facet_item=french",
            group.options[1].href,
        )
        assertTrue(group.options.none { it.active })
    }

    @Test
    fun `komga shaped minimal publication takes its thumbnail as cover`() {
        val feed = parse(
            """
            {"metadata":{"title":"Saga"},"publications":[
              {"metadata":{"title":"Saga 1"},
               "links":[{"rel":"self","href":"https://komga.test/opds/v2/publications/saga-1",
                         "type":"application/opds-publication+json"}],
               "images":[{"href":"https://komga.test/opds/v2/books/saga-1/thumbnail",
                          "type":"image/jpeg","rel":"thumbnail"}]}]}
            """.trimIndent(),
            base = "https://komga.test/opds/v2",
        )

        val book = feed.entries.single()
        assertEquals("Saga 1", book.title)
        assertEquals("https://komga.test/opds/v2/books/saga-1/thumbnail", book.coverUrl)
        assertTrue(book.acquisitions.isEmpty())
        assertTrue(book.isPublication)
        assertTrue(!feed.isNavigation)
    }

    @Test
    fun `subjects and authors accept names as objects or strings`() {
        val feed = parse(
            """
            {"@context":"https://readium.org/webpub-manifest/context.jsonld","publications":[
              {"metadata":{"title":"Tagged","identifier":"tag:book:1",
                           "author":["Anna Author",{"name":"Bob Author"}],
                           "subject":["Fantasy",{"name":"Space opera"},"Fantasy"]}}]}
            """.trimIndent(),
        )

        val book = feed.entries.single()
        assertEquals("Anna Author", book.author)
        assertEquals(listOf("Fantasy", "Space opera"), book.categories)
        // A non-http identifier is not an entry URL.
        assertNull(book.id)
    }

    @Test
    fun `json and atom are dispatched by body`() {
        val atom = OpdsFeeds.parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title></feed>""".toByteArray(),
            "https://h.test/opds",
        )
        assertEquals("Atom", (atom as Outcome.Ok).value.title)

        val json = OpdsFeeds.parse(
            """{"@context":"https://readium.org/webpub-manifest/context.jsonld","metadata":{"title":"Json"}}"""
                .toByteArray(),
            "https://h.test/opds",
        )
        assertEquals("Json", (json as Outcome.Ok).value.title)

        val bom = OpdsFeeds.parse(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                """{"metadata":{"title":"Bom"},"navigation":[{"title":"S","href":"s"}]}""".toByteArray(),
            "https://h.test/opds",
        )
        assertEquals("Bom", (bom as Outcome.Ok).value.title)

        val other = OpdsFeeds.parse("""{"status":"error"}""".toByteArray(), "https://h.test/opds")
        assertEquals(OpdsError.NotAFeed, (other as Outcome.Err).error)

        val broken = OpdsFeeds.parse("""{"@context":""".toByteArray(), "https://h.test/opds")
        assertEquals(OpdsError.Malformed, (broken as Outcome.Err).error)

        val html = OpdsFeeds.parse("<html>login</html>".toByteArray(), "https://h.test/opds")
        assertEquals(OpdsError.NotAFeed, (html as Outcome.Err).error)
    }

    @Test
    fun `search templates cover the legacy and rfc 6570 forms`() {
        assertEquals(
            "https://c.test/search?q=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0+%D0%B8+%D0%BC%D0%B8%D1%80",
            OpdsSearchTemplate.substitute("https://c.test/search?q={searchTerms}", "война и мир"),
        )
        assertEquals(
            "https://c.test/catalog?query=a+b&type=search",
            OpdsSearchTemplate.substitute("https://c.test/catalog{?query}&type=search", "a b"),
        )
        assertEquals(
            "https://c.test/catalog?searchTerms=a+b",
            OpdsSearchTemplate.substitute("https://c.test/catalog{?searchTerms}", "a b"),
        )
        // An expression we cannot fill is left alone instead of guessing.
        assertEquals(
            "https://c.test/catalog{?q}&type=search",
            OpdsSearchTemplate.substitute("https://c.test/catalog{?q}&type=search", "a b"),
        )
        assertTrue(OpdsSearchTemplate.isTemplated("https://c.test/catalog{?query}"))
        assertTrue(!OpdsSearchTemplate.isTemplated("https://c.test/catalog{?q}"))
        assertTrue(!OpdsSearchTemplate.isTemplated("https://c.test/opds"))
    }

    /** The archive.org publication body, with the title kept short for assertions. */
    private fun publicationJson(identifier: String): String =
        """
        {"metadata":{"title":"A reply to Mr. Maxwell's Answer",
                     "identifier":"https://archive.org/details/$identifier",
                     "type":"http://schema.org/Book",
                     "published":"2018-12-06T16:18:57Z",
                     "language":["en"],
                     "description":"2 unnumbered leaves, viii, 107 pages ; (8vo)",
                     "author":[{"name":"Kirkland, Thomas, 1722-1798"}],
                     "numberOfPages":122},
         "links":[{"href":"https://archive.org/details/$identifier&view=theater",
                   "type":"text/html","rel":"http://opds-spec.org/acquisition/sample"},
                  {"href":"https://archive.org/services/loans/loan/?action=webpub&opds=1&identifier=$identifier",
                   "type":"application/opds-publication+json","rel":"self","title":"Internet Archive"},
                  {"href":"https://archive.org/services/opds/book/$identifier?glob_pattern=*pdf",
                   "type":"application/pdf","rel":"http://opds-spec.org/acquisition/open-access",
                   "title":"Internet Archive","properties":{"availability":{"state":"available"}}}],
         "images":[{"href":"https://archive.org/download/$identifier/__ia_thumb.jpg",
                    "type":"image/jpeg","rel":"cover","height":1400,"width":800},
                   {"href":"https://archive.org/download/$identifier/__ia_thumb.jpg",
                    "type":"image/jpeg","height":700,"width":400}]}
        """.trimIndent()
}
