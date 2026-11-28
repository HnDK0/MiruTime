package dev.mirutime.core.engine.binding

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlResolverTest {

    private val base = "http://a/b/c/d;p?q"

    private fun assertResolves(reference: String, expected: String, from: String = base) =
        assertEquals("'$reference' against '$from'", expected, UrlResolver.resolve(from, reference))

    // RFC 3986 section 5.4.1
    @Test
    fun `normal examples of the RFC`() {
        mapOf(
            "g:h" to "g:h",
            "g" to "http://a/b/c/g",
            "./g" to "http://a/b/c/g",
            "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g",
            "//g" to "http://g",
            "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s",
            "g#s" to "http://a/b/c/g#s",
            "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x",
            "g;x" to "http://a/b/c/g;x",
            "g;x?y#s" to "http://a/b/c/g;x?y#s",
            "" to "http://a/b/c/d;p?q",
            "." to "http://a/b/c/",
            "./" to "http://a/b/c/",
            ".." to "http://a/b/",
            "../" to "http://a/b/",
            "../g" to "http://a/b/g",
            "../.." to "http://a/",
            "../../" to "http://a/",
            "../../g" to "http://a/g",
        ).forEach { (reference, expected) -> assertResolves(reference, expected) }
    }

    // RFC 3986 section 5.4.2
    @Test
    fun `abnormal examples of the RFC`() {
        mapOf(
            "../../../g" to "http://a/g",
            "../../../../g" to "http://a/g",
            "/./g" to "http://a/g",
            "/../g" to "http://a/g",
            "g." to "http://a/b/c/g.",
            ".g" to "http://a/b/c/.g",
            "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g",
            "./../g" to "http://a/b/g",
            "./g/." to "http://a/b/c/g/",
            "g/./h" to "http://a/b/c/g/h",
            "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y",
            "g;x=1/../y" to "http://a/b/c/y",
            "g?y/./x" to "http://a/b/c/g?y/./x",
            "g?y/../x" to "http://a/b/c/g?y/../x",
            "g#s/./x" to "http://a/b/c/g#s/./x",
            "g#s/../x" to "http://a/b/c/g#s/../x",
            "http:g" to "http:g",
        ).forEach { (reference, expected) -> assertResolves(reference, expected) }
    }

    // What java.net.URI.resolve got wrong for a page link.
    @Test
    fun `a query-only link keeps the path of the page`() {
        assertResolves("?page=2", "https://site.com/list/1?page=2", from = "https://site.com/list/1")
        assertResolves("?page=2", "https://site.com/list/1?page=2", from = "https://site.com/list/1?page=1#top")
    }

    @Test
    fun `dots above the root are dropped`() {
        assertResolves("../../novel/1", "https://site.com/novel/1", from = "https://site.com/a")
    }

    @Test
    fun `characters a page puts in a link are escaped instead of failing`() {
        assertResolves("/x y.html", "https://site.com/x%20y.html", from = "https://site.com/a/")
        assertResolves("x|y.html", "https://site.com/a/x%7Cy.html", from = "https://site.com/a/")
        assertResolves("c.html", "https://site.com/a%20b/c.html", from = "https://site.com/a b/")
    }

    @Test
    fun `non-ascii text and existing escapes are kept`() {
        assertResolves("/小说/第1章", "https://site.com/小说/第1章", from = "https://site.com")
        assertResolves("/a%20b?q=%E5%B0%8F", "https://site.com/a%20b?q=%E5%B0%8F", from = "https://site.com")
    }

    @Test
    fun `usual links against a site root keep their old result`() {
        assertResolves("novel/1.html", "https://site.com/novel/1.html", from = "https://site.com")
        assertResolves("novel/1.html", "https://site.com/novel/1.html", from = "https://site.com/")
        assertResolves("/novel/1.html", "https://site.com/novel/1.html", from = "https://site.com")
        assertResolves("//cdn.com/x.jpg", "https://cdn.com/x.jpg", from = "https://site.com")
        assertResolves("c.html", "https://site.com/a/c.html", from = "https://site.com/a/b.html")
        assertResolves("../2", "https://site.com/2", from = "https://site.com/list/1")
        assertResolves("#frag", "https://site.com/a/b#frag", from = "https://site.com/a/b")
        assertResolves("https://other.com/x?y=1", "https://other.com/x?y=1", from = "https://site.com/a")
    }

    @Test
    fun `a time-like first segment is a relative path`() {
        assertResolves("12:30/x", "https://site.com/a/12:30/x", from = "https://site.com/a/b")
    }
}
