package dev.mirutime.core.opds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpdsDescriptionCleaningTest {
    @Test
    fun comparisonsInPlainTextSurviveCleaning() {
        assertEquals("It works when x < 3 and y > 2.", OpdsParser.cleanDescription("It works when x < 3 and y > 2."))
    }

    @Test
    fun realMarkupIsStillStripped() {
        val cleaned = OpdsParser.cleanDescription("<p>Hello <b>world</b></p>").orEmpty()
        assertFalse("<" in cleaned, cleaned)
        assertTrue("Hello" in cleaned && "world" in cleaned, cleaned)
    }

    @Test
    fun commentsAndProcessingInstructionsAreStillStripped() {
        assertEquals("Visible", OpdsParser.cleanDescription("<!-- hidden -->Visible"))
        assertEquals("Text", OpdsParser.cleanDescription("<?xml version=\"1.0\"?>Text"))
    }
}
