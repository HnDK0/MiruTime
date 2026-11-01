package dev.mirutime.core.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicInfoTest {

    @Test
    fun `comic info fields and genres are parsed`() {
        val meta = parseComicInfo(
            """
            <ComicInfo>
              <Title>Berserk Vol 1</Title>
              <Series>Berserk</Series>
              <Volume>1</Volume>
              <Summary>Guts and Griffith.</Summary>
              <Writer>Kentaro Miura</Writer>
              <Penciller>Kentaro Miura</Penciller>
              <Genre>Action, Fantasy; Drama</Genre>
            </ComicInfo>
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals("Berserk Vol 1", meta?.title)
        assertEquals("Berserk", meta?.series)
        assertEquals("1", meta?.volume)
        assertEquals("Guts and Griffith.", meta?.summary)
        assertEquals("Kentaro Miura", meta?.author)
        assertEquals("Kentaro Miura", meta?.artist)
        assertEquals(listOf("Action", "Fantasy", "Drama"), meta?.genres)
    }

    @Test
    fun `series and volume are the fallback title`() {
        val meta = parseComicInfo(
            "<ComicInfo><Series>Vagabond</Series><Volume>3</Volume></ComicInfo>".encodeToByteArray(),
        )
        assertEquals("Vagabond Vol 3", meta?.displayTitle)
    }

    @Test
    fun `broken xml is not metadata`() {
        assertNull(parseComicInfo("<ComicInfo><Title>oops".encodeToByteArray()))
    }
}
