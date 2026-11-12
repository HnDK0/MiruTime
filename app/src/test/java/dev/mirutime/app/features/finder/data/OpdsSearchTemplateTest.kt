package dev.mirutime.app.features.finder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// An OPDS feed may advertise search as an OpenSearch description instead of an
// inline template; the document names the Atom template the app must use.
class OpdsSearchTemplateTest {

    @Test
    fun `picks the first atom template carrying the search placeholder`() {
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <ShortName>Catalog</ShortName>
              <Url type="text/html" template="https://c.test/search?q={searchTerms}"/>
              <Url type="application/atom+xml;profile=opds-catalog" template="https://c.test/atom?q={searchTerms}&amp;page=1"/>
            </OpenSearchDescription>
        """.trimIndent()

        assertEquals("https://c.test/atom?q={searchTerms}&page=1", opensearchTemplate(document))
    }

    @Test
    fun `a description without an atom search template yields null`() {
        val document = """
            <OpenSearchDescription>
              <Url type="text/html" template="https://c.test/search?q={searchTerms}"/>
              <Url type="application/atom+xml" template="https://c.test/atom"/>
            </OpenSearchDescription>
        """.trimIndent()

        assertNull(opensearchTemplate(document))
    }

    @Test
    fun `a relative template resolves against the description url`() {
        assertEquals(
            "https://c.test/opds/search?q={searchTerms}",
            resolveUrlAgainst("https://c.test/opds/osd.xml", "/opds/search?q={searchTerms}"),
        )
        assertEquals(
            "https://other.test/search?q={searchTerms}",
            resolveUrlAgainst("https://c.test/opds/osd.xml", "https://other.test/search?q={searchTerms}"),
        )
    }

    // OPDS 2 advertises search as an RFC 6570 template; the same substitution
    // serves both dialects.
    @Test
    fun `an opds 2 query template fills the query parameter`() {
        assertEquals(
            "https://c.test/catalog?query=war+and+peace&type=search",
            substituteSearchTerms("https://c.test/catalog{?query}&type=search", "war and peace"),
        )
        assertEquals(
            "https://c.test/search?q=war+and+peace",
            substituteSearchTerms("https://c.test/search?q={searchTerms}", "war and peace"),
        )
    }
}
