package dev.mirutime.core.opds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpdsLinkFallbackTest {
    private val base = "https://h/cat/"

    @Test
    fun charactersJavaNetUriRejectDoNotDropTheLink() {
        assertEquals("https://h/cat/get/book%7Cv2.epub", OpdsParser.resolveHref(base, "get/book|v2.epub"))
        assertEquals("https://h/cat/get/a%3Cb%3E.epub", OpdsParser.resolveHref(base, "get/a<b>.epub"))
        assertEquals("https://h/cat/files/a[1].epub", OpdsParser.resolveHref(base, "files/a[1].epub"))
        assertEquals("http://[::1]/a[1]", OpdsParser.resolveHref(base, "http://[::1]/a[1]"))
    }

    @Test
    fun aPercentSignThatStartsNoEscapeDoesNotDropTheLink() {
        assertEquals("https://h/cat/get/100%.epub", OpdsParser.resolveHref(base, "get/100%.epub"))
        assertEquals("https://h/cat/get/a%zz.epub", OpdsParser.resolveHref(base, "get/a%zz.epub"))
    }

    @Test
    fun controlCharactersAndNonAsciiSpacesDoNotDropTheLink() {
        assertEquals("https://h/cat/get/line%0Abreak.epub", OpdsParser.resolveHref(base, "get/line\nbreak.epub"))
        assertEquals("https://h/cat/get/nbsp\u00A0x.epub", OpdsParser.resolveHref(base, "get/nbsp\u00A0x.epub"))
    }

    // Links that java.net.URI resolves today must come out exactly as they did.
    @Test
    fun linksThatResolvedBeforeKeepTheirExactResult() {
        assertEquals("https://h/cat/get/a%20b.epub", OpdsParser.resolveHref(base, "get/a b.epub"))
        assertEquals("https://h/cat/search?filter[genre]=x", OpdsParser.resolveHref(base, "search?filter[genre]=x"))
        assertEquals("https://h/cat/search/{searchTerms}", OpdsParser.resolveHref(base, "search/{searchTerms}"))
        assertEquals("https://cdn.example.com/a%20b.epub", OpdsParser.resolveHref(base, "//cdn.example.com/a b.epub"))
        assertEquals("https://h/cat/get/ya%20codificado.epub", OpdsParser.resolveHref(base, "get/ya%20codificado.epub"))
        assertEquals("https://h/cat/get/caf\u00e9.epub", OpdsParser.resolveHref(base, "get/caf\u00e9.epub"))
    }

    @Test
    fun anEmptyHrefIsStillNull() {
        assertNull(OpdsParser.resolveHref(base, "   "))
    }
}
