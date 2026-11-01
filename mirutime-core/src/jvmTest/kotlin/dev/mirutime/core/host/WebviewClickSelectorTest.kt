package dev.mirutime.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// `click` is a selector, not a script: the Lua bridge and the hidden WebView
// both accept only these two shapes, so plugin text never becomes JS.
class WebviewClickSelectorTest {

    @Test
    fun plainSelectorPassesThrough() {
        assertEquals("a", WebviewClickSelector.extract("a"))
        assertEquals("#main .item > a:hover", WebviewClickSelector.extract("#main .item > a:hover"))
        assertEquals("""a[data-x="y"]""", WebviewClickSelector.extract("""a[data-x="y"]"""))
        assertEquals("li:nth-child(2n+1)", WebviewClickSelector.extract("li:nth-child(2n+1)"))
    }

    @Test
    fun jqueryDoubleQuotesAreUnwrapped() {
        assertEquals("a", WebviewClickSelector.extract("""$("a").click()"""))
    }

    @Test
    fun jquerySingleQuotesAreUnwrapped() {
        assertEquals("a", WebviewClickSelector.extract("$('a').click()"))
        assertEquals("""a[title="x"]""", WebviewClickSelector.extract("""$('a[title="x"]').click()"""))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("a", WebviewClickSelector.extract("  $('a').click()  "))
        assertEquals("a", WebviewClickSelector.extract("  a  "))
    }

    @Test
    fun arbitraryJsIsRejected() {
        assertNull(WebviewClickSelector.extract("alert(1)"))
        assertNull(WebviewClickSelector.extract("a);alert(1"))
        assertNull(WebviewClickSelector.extract("window.location='x'"))
        assertNull(WebviewClickSelector.extract("$('a').mouseover()"))
        assertNull(WebviewClickSelector.extract("$('a').click();alert(1)"))
    }

    @Test
    fun quotesAndBackslashes() {
        assertEquals("""a[title="x"]""", WebviewClickSelector.extract("""a[title="x"]"""))
        assertNull(WebviewClickSelector.extract("""a\b"""))
        assertNull(WebviewClickSelector.extract("$('a\\b').click()"))
        assertNull(WebviewClickSelector.extract("$('a').click() + alert(1)"))
    }

    @Test
    fun newlineAndNulAreRejected() {
        assertNull(WebviewClickSelector.extract("a\nb"))
        assertNull(WebviewClickSelector.extract("a\rb"))
        assertNull(WebviewClickSelector.extract("a\u0000b"))
    }

    @Test
    fun lengthLimitIsEnforced() {
        val max = "a".repeat(WebviewClickSelector.MAX_LENGTH)
        assertEquals(max, WebviewClickSelector.extract(max))
        assertNull(WebviewClickSelector.extract(max + "a"))
    }

    @Test
    fun blankOrNullIsRejected() {
        assertNull(WebviewClickSelector.extract(null))
        assertNull(WebviewClickSelector.extract(""))
        assertNull(WebviewClickSelector.extract("   "))
    }
}
