package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryRulesTest {

    @Test
    fun `push trims dedupes case-insensitively and moves repeats to the top`() {
        var history = emptyList<String>()
        history = SearchHistoryRules.push(history, "  Dragon  ")
        history = SearchHistoryRules.push(history, "Cultivation")
        history = SearchHistoryRules.push(history, "dragon")

        assertEquals(listOf("dragon", "Cultivation"), history)
    }

    @Test
    fun `push keeps only the newest five entries`() {
        var history = emptyList<String>()
        (1..7).forEach { history = SearchHistoryRules.push(history, "q$it") }

        assertEquals(listOf("q7", "q6", "q5", "q4", "q3"), history)
        assertEquals(SearchHistoryRules.Max, history.size)
    }

    @Test
    fun `blank queries are ignored and remove is case-insensitive`() {
        val history = listOf("Dragon", "Cultivation")
        assertEquals(history, SearchHistoryRules.push(history, "   "))
        assertEquals(listOf("Cultivation"), SearchHistoryRules.remove(history, "dragon"))
    }
}
