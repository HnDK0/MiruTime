package dev.mirutime.app.features.opds

import dev.mirutime.app.features.opds.data.authOriginMatches
import dev.mirutime.app.features.opds.data.canonicalCatalogUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsCatalogUrlTest {

    @Test
    fun `trailing slash and letter case do not make a second catalogue`() {
        assertEquals(
            canonicalCatalogUrl("https://c.test/opds"),
            canonicalCatalogUrl("https://c.test/opds/"),
        )
        assertEquals("https://c.test/opds", canonicalCatalogUrl("HTTPS://C.TEST/opds/"))
    }

    @Test
    fun `a default port is dropped and a real one kept`() {
        assertEquals(
            canonicalCatalogUrl("https://c.test/opds"),
            canonicalCatalogUrl("https://c.test:443/opds"),
        )
        assertEquals(
            canonicalCatalogUrl("http://c.test/opds"),
            canonicalCatalogUrl("http://c.test:80/opds"),
        )
        assertEquals("https://c.test:8443/opds", canonicalCatalogUrl("https://c.test:8443/opds/"))
    }

    @Test
    fun `www is a different host`() {
        assertFalse(
            canonicalCatalogUrl("https://www.c.test/opds") ==
                canonicalCatalogUrl("https://c.test/opds"),
        )
    }

    @Test
    fun `auth origin survives an upgrade and refuses a downgrade`() {
        // Typical misconfigured catalogue: typed http, server redirects to https.
        assertTrue(authOriginMatches("http://a.test/opds", "https://a.test/opds/x"))
        assertTrue(authOriginMatches("https://a.test/opds", "https://a.test/books/1.epub"))
        assertTrue(authOriginMatches("https://A.TEST/opds", "https://a.test:443/opds/x"))
        // Never send the password down to plain http.
        assertFalse(authOriginMatches("https://a.test/opds", "http://a.test/opds"))
        assertFalse(authOriginMatches("https://a.test/opds", "https://cdn.test/1.epub"))
        assertFalse(authOriginMatches("http://a.test/opds", "https://a.test:8443/opds"))
        assertFalse(authOriginMatches("not a url", "https://a.test/opds"))
    }
}
