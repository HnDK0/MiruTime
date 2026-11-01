package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockKind
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSearchTest {

    @Test
    fun `normalize trims fragments and keeps reading order`() {
        val text = SelectionSearch.normalize(
            listOf("  first sentence  ", "\n", "second sentence\t"),
        )

        assertEquals("first sentence\nsecond sentence", text)
    }

    @Test
    fun `blank selection normalizes to empty`() {
        assertEquals("", SelectionSearch.normalize(listOf(" ", "\n", "\t")))
        assertEquals("", SelectionSearch.normalize(emptyList()))
        assertTrue(SelectionSearch.normalize(listOf(" ")).isEmpty())
    }

    @Test
    fun `only prose blocks offer search`() {
        assertTrue(SelectionSearch.isSearchable(BlockKind.Heading))
        assertTrue(SelectionSearch.isSearchable(BlockKind.Paragraph))
        assertFalse(SelectionSearch.isSearchable(BlockKind.Image))
        assertFalse(SelectionSearch.isSearchable(BlockKind.Divider))
    }

    @Test
    fun `fallback url encodes the selected query`() {
        assertEquals(
            "https://www.google.com/search?q=hello+world",
            SelectionSearch.fallbackUrl("hello world"),
        )
        val encoded = SelectionSearch.fallbackUrl("Привет мир").substringAfter("q=")
        assertEquals(
            "Привет мир",
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()),
        )
    }
}
