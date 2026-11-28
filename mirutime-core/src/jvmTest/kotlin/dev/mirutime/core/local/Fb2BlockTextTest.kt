package dev.mirutime.core.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun fb2(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><book-title>T</book-title><author><first-name>A</first-name><last-name>B</last-name></author></title-info></description>
<body>$body</body></FictionBook>"""

class Fb2BlockTextTest {
    @Test
    fun versesAndCitedParagraphsStayOnSeparateLinesWithoutWhitespaceBetweenTags() {
        val body = "<section><poem><stanza><v>one</v><v>two</v></stanza></poem><cite><p>A</p><p>B</p></cite></section>"
        val chapter = parseFb2(fb2(body).toByteArray()).chapters.single()
        assertTrue("one\ntwo" in chapter.body, chapter.body)
        assertTrue("A\nB" in chapter.body, chapter.body)
    }

    @Test
    fun aTitleWithSeveralParagraphsReadsAsOneLine() {
        val body = "<section><title><p>Chapter</p><p>One</p></title><p>Text of the chapter.</p></section>"
        assertEquals("Chapter One", parseFb2(fb2(body).toByteArray()).chapters.single().title)
    }

    @Test
    fun hardWrappedLinesAreJoinedButParagraphsStaySeparate() {
        val body = "<section><cite><p>one sentence that the editor\n   wrapped in two lines</p><p>second</p></cite></section>"
        val chapter = parseFb2(fb2(body).toByteArray()).chapters.single()
        assertTrue("one sentence that the editor wrapped in two lines\nsecond" in chapter.body, chapter.body)
    }

    @Test
    fun anAnnotationWithSeveralParagraphsKeepsThemApart() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>T</book-title><annotation><p>First.</p><p>Second.</p></annotation></title-info></description>
<body><section><p>Text.</p></section></body></FictionBook>"""
        assertEquals("First.\nSecond.", parseFb2(xml.toByteArray()).description)
    }
}
