package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableChoiceSheetTest {
    private val options = listOf(
        ChoiceOption("roboto", "Roboto", "Neutral sans"),
        ChoiceOption("serif", "Serif", "Old style"),
        ChoiceOption("mono", "Monospace", null),
    )

    @Test
    fun filtersByLabelCaseInsensitive() {
        assertEquals(listOf("roboto"), filterChoiceOptions(options, "rob").map { it.key })
    }

    @Test
    fun filtersByDescription() {
        assertEquals(listOf("serif"), filterChoiceOptions(options, "old style").map { it.key })
    }

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(options, filterChoiceOptions(options, "   "))
    }

    @Test
    fun searchAppearsOnlyAboveFifteenRows() {
        assertFalse(searchableChoiceNeedsSearch(15))
        assertTrue(searchableChoiceNeedsSearch(16))
    }
}
