package dev.mirutime.core.opds

import kotlin.test.Test
import kotlin.test.assertEquals

class OpdsSearchSpaceEncodingTest {
    @Test
    fun aSpaceIsPercentTwentyInAPathTemplate() {
        assertEquals(
            "https://h/search/dark%20tower",
            OpdsSearchTemplate.substitute("https://h/search/{searchTerms}", "dark tower"),
        )
    }

    @Test
    fun aSpaceStaysAPlusInAQueryTemplate() {
        assertEquals(
            "https://h/search?q=dark+tower",
            OpdsSearchTemplate.substitute("https://h/search?q={searchTerms}", "dark tower"),
        )
        assertEquals(
            "https://h/search?query=dark+tower",
            OpdsSearchTemplate.substitute("https://h/search{?query}", "dark tower"),
        )
    }

    @Test
    fun aLiteralPlusStaysEncoded() {
        assertEquals(
            "https://h/search?q=c%2B%2B",
            OpdsSearchTemplate.substitute("https://h/search?q={searchTerms}", "c++"),
        )
    }
}
