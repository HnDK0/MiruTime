package dev.mirutime.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// RFC 3986 §5.2 resolution of a Location header. The rule that matters: a
// reference without a query part has NO query, it does not inherit the base's.
class RedirectLocationResolveTest {

    @Test
    fun locationWithoutQueryDropsBaseQuery() {
        assertEquals(
            "https://h/search-adv",
            resolveRedirectLocation("https://h/search-adv?genre_match=all&page=1", "/search-adv"),
        )
    }

    @Test
    fun locationWithQueryReplacesQuery() {
        assertEquals(
            "https://h/search?keyword=x",
            resolveRedirectLocation("https://h/search-adv?genre_match=all&page=1", "/search?keyword=x"),
        )
    }

    @Test
    fun locationKeepsQueryWhenPresent() {
        assertEquals(
            "https://cdn.h/x?a=1&b=2",
            resolveRedirectLocation("https://h/search-adv?genre_match=all", "https://cdn.h/x?a=1&b=2"),
        )
    }

    @Test
    fun schemeRelativeLocation() {
        assertEquals(
            "https://cdn.h/x",
            resolveRedirectLocation("https://h/a?q=1", "//cdn.h/x"),
        )
    }

    @Test
    fun relativePathLocation() {
        assertEquals(
            "https://h/a/foo/bar",
            resolveRedirectLocation("https://h/a/b?q=1", "foo/bar"),
        )
    }

    @Test
    fun dotSegmentsAreRemoved() {
        assertEquals(
            "https://h/x",
            resolveRedirectLocation("https://h/a/b?q=1", "../x"),
        )
    }

    @Test
    fun queryOnlyLocationReplacesQuery() {
        assertEquals(
            "https://h/a/b?q=2",
            resolveRedirectLocation("https://h/a/b?q=1", "?q=2"),
        )
    }

    @Test
    fun fragmentOnlyIsNotAHop() {
        assertNull(resolveRedirectLocation("https://h/a/b?q=1", "#x"))
    }
}
