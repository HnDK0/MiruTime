package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P0 features: navigation catalogs, facets, sort/search links, indirect acquisitions. */
class OpdsFeedFeaturesTest {

    private fun parse(xml: String, base: String): OpdsFeed = when (val feed = OpdsParser.parse(xml.toByteArray(), base)) {
        is Outcome.Ok -> feed.value
        is Outcome.Err -> kotlin.test.fail("expected a feed, got ${feed.error}")
    }

    @Test
    fun `gutenberg root feed is navigation with an opensearch search link`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Project Gutenberg</title>
              <id>https://www.gutenberg.org/ebooks/</id>
              <link rel="search" href="/ebooks/search.opensearch" type="application/opensearchdescription+xml"/>
              <entry>
                <title>Most Popular</title>
                <link rel="subsection" href="/ebooks/search.opds/?sort_order=downloads" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              </entry>
              <entry>
                <title>New Releases</title>
                <link rel="subsection" href="/ebooks/search.opds/?sort_order=release_date" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              </entry>
              <entry>
                <title>Random</title>
                <link rel="subsection" href="/ebooks/search.opds/?sort_order=random" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              </entry>
            </feed>
            """.trimIndent(),
            base = "https://www.gutenberg.org/ebooks/",
        )

        assertEquals(3, feed.entries.size)
        assertEquals(3, feed.entries.count { it.acquisitions.isEmpty() })
        assertTrue(feed.isNavigation)
        assertEquals("application/opensearchdescription+xml", feed.searchLink?.type)
        assertEquals("https://www.gutenberg.org/ebooks/search.opensearch", feed.searchLink?.href)
        assertTrue(feed.facets.isEmpty())
    }

    @Test
    fun `gutenberg search page keeps paging and both formats`() {
        val books = (1..25).joinToString("\n") { n ->
            """
            <entry>
              <title>Book $n</title>
              <id>https://www.gutenberg.org/ebooks/$n</id>
              <link rel="http://opds-spec.org/acquisition/open-access" href="/ebooks/$n.epub.images" type="application/epub+zip"/>
              <link rel="http://opds-spec.org/acquisition/open-access" href="/ebooks/$n.kindle.images" type="application/x-mobipocket-ebook"/>
            </entry>
            """.trimIndent()
        }
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Search results</title>
              <link rel="self" href="/ebooks/search.opds/?sort_order=downloads" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              <link rel="start" href="/ebooks/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
              <link rel="next" href="/ebooks/search.opds?start_index=26" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              <entry>
                <title>Back to start</title>
                <link rel="subsection" href="/ebooks/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
              </entry>
            $books
            </feed>
            """.trimIndent(),
            base = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads",
        )

        assertEquals(25, feed.entries.count { it.acquisitions.isNotEmpty() })
        assertTrue(!feed.isNavigation)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds?start_index=26", feed.nextUrl)

        val book = feed.entries.first { it.acquisitions.isNotEmpty() }
        assertEquals(OpdsFormat.EPUB, book.bestAcquisition()?.first)
        assertEquals("https://www.gutenberg.org/ebooks/1.epub.images", book.bestAcquisition()?.second?.href)
        assertTrue(book.acquisitionLinks.any { it.type == "application/x-mobipocket-ebook" })
    }

    @Test
    fun `facets are grouped in feed order with active flags and counts`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:opds="http://opds-spec.org/2010/catalog"
                  xmlns:thr="http://purl.org/syndication/thread/1.0">
              <title>Faceted</title>
              <link rel="http://opds-spec.org/facet" href="/opds?lang=en" title="English"
                    opds:facetGroup="Language" opds:activeFacet="true" thr:count="12"/>
              <link rel="http://opds-spec.org/facet" href="/opds?lang=ru" title="Russian"
                    opds:facetGroup="Language" thr:count="4"/>
              <link rel="http://opds-spec.org/facet" href="/opds?available=1" title="Available now"
                    opds:facetGroup="Availability"/>
            </feed>
            """.trimIndent(),
            base = "https://catalog.test/opds",
        )

        assertEquals(listOf("Language", "Availability"), feed.facets.map { it.label })

        val language = feed.facets[0]
        assertEquals(listOf("English", "Russian"), language.options.map { it.label })
        assertEquals(listOf(true, false), language.options.map { it.active })
        assertEquals(listOf(12, 4), language.options.map { it.count })
        assertEquals("https://catalog.test/opds?lang=en", language.options[0].href)

        val availability = feed.facets[1]
        assertEquals(1, availability.options.size)
        assertEquals(false, availability.options.single().active)
        assertNull(availability.options.single().count)
    }

    @Test
    fun `indirect acquisition is exposed but yields no direct format`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:opds="http://opds-spec.org/2010/catalog">
              <title>Shop</title>
              <entry>
                <title>Buy the book</title>
                <link rel="http://opds-spec.org/acquisition" type="text/html" href="/item/1/buy/">
                  <opds:indirectAcquisition type="application/epub+zip"/>
                </link>
              </entry>
            </feed>
            """.trimIndent(),
            base = "https://shop.test/opds",
        )

        val entry = feed.entries.single()
        val link = entry.acquisitionLinks.single()
        assertEquals("http://opds-spec.org/acquisition", link.rel)
        assertEquals("https://shop.test/item/1/buy/", link.href)
        assertEquals("text/html", link.type)
        assertEquals("application/epub+zip", link.indirect.single().type)
        // No href on the nested element: it inherits the parent page.
        assertEquals("https://shop.test/item/1/buy/", link.indirect.single().href)
        assertNull(entry.bestAcquisition())
    }

    @Test
    fun `sample and buy links stay visible but are never auto-downloaded`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Mixed</title>
              <entry>
                <title>Sample only</title>
                <link rel="http://opds-spec.org/acquisition/sample" href="/b/1.sample.epub" type="application/epub+zip"/>
              </entry>
              <entry>
                <title>Buy only</title>
                <link rel="http://opds-spec.org/acquisition/buy" href="/b/2.epub" type="application/epub+zip"/>
              </entry>
            </feed>
            """.trimIndent(),
            base = "https://shop.test/opds",
        )

        assertEquals(2, feed.entries.size)
        for (entry in feed.entries) {
            assertEquals(1, entry.acquisitionLinks.size)
            assertEquals(1, entry.acquisitions.size)
            assertNull(entry.bestAcquisition())
        }
        assertEquals(
            "http://opds-spec.org/acquisition/sample",
            feed.entries[0].acquisitionLinks.single().rel,
        )
        assertEquals(
            "http://opds-spec.org/acquisition/buy",
            feed.entries[1].acquisitionLinks.single().rel,
        )
    }

    @Test
    fun `feed sort links are separate from paging`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Sorted</title>
              <link rel="http://opds-spec.org/sort/new" href="/opds?sort=new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              <link rel="http://opds-spec.org/sort/popular" href="/opds?sort=popular" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              <link rel="next" href="/opds?page=2" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
            </feed>
            """.trimIndent(),
            base = "https://catalog.test/opds",
        )

        assertEquals(
            listOf("http://opds-spec.org/sort/new", "http://opds-spec.org/sort/popular"),
            feed.sortLinks.map { it.rel },
        )
        assertEquals("https://catalog.test/opds?sort=new", feed.sortLinks[0].href)
        assertEquals("https://catalog.test/opds?page=2", feed.nextUrl)
        assertTrue(feed.sortLinks.none { it.rel == "next" })
    }
}
