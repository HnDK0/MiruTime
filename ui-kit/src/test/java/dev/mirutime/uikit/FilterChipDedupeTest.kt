package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterChipDedupeTest {

    @Test
    fun `chip options dedupe by id keeping the first label`() {
        val options = listOf(
            ChipOption("novel", "Novel"),
            ChipOption("novel", "Duplicate"),
            ChipOption("manga", "Manga"),
        )

        assertEquals(
            listOf("novel" to "Novel", "manga" to "Manga"),
            dedupeChipOptions(options).map { it.id to it.label },
        )
    }
}
