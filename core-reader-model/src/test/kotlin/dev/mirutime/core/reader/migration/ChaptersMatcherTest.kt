package dev.mirutime.core.reader.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersMatcherTest {
    private val matcher = ChaptersMatcher()

    private fun candidate(key: String, title: String, volume: Int? = null) =
        ChapterMatchCandidate(key = key, title = title, volume = volume)

    @Test
    fun `matches english chapter labels and punctuation`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "Chapter 1"),
                candidate("o2", "Ch. 2"),
                candidate("o3", "Episode 3"),
            ),
            new = listOf(
                candidate("n1", "chapter 1"),
                candidate("n2", "Chapter 2"),
                candidate("n3", "EP 3"),
            ),
        )

        assertEquals(listOf("o1" to "n1", "o2" to "n2", "o3" to "n3"), result.pairs())
        assertTrue(result.unmatchedOld.isEmpty())
        assertTrue(result.unmatchedNew.isEmpty())
        assertEquals(1f, result.score)
    }

    @Test
    fun `volume is part of the identity`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "Volume 2 Chapter 3"),
                candidate("o2", "Vol. 1 Ch. 3"),
                candidate("o3", "Chapter 4", volume = 2),
            ),
            new = listOf(
                candidate("n1", "Vol 2 Ch 3"),
                candidate("n2", "Volume 2 Chapter 3"),
                candidate("n3", "Chapter 4"),
            ),
        )

        assertEquals(listOf("o1" to "n1"), result.pairs())
        assertEquals(listOf("o2", "o3"), result.unmatchedOld.map { it.key })
        assertEquals(listOf("n2", "n3"), result.unmatchedNew.map { it.key })
    }

    @Test
    fun `matches east asian chapter markers`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "第15話"),
                candidate("o2", "第3章"),
                candidate("o3", "7화"),
                candidate("o4", "제 9 화"),
            ),
            new = listOf(
                candidate("n1", "15話"),
                candidate("n2", "Chapter 3"),
                candidate("n3", "第7章"),
                candidate("n4", "9화"),
            ),
        )

        assertEquals(
            listOf("o1" to "n1", "o2" to "n2", "o3" to "n3", "o4" to "n4"),
            result.pairs(),
        )
    }

    @Test
    fun `leading number matches a labelled chapter`() {
        val result = matcher.match(
            old = listOf(candidate("o1", "12. A beginning"), candidate("o2", "4.5 Interlude")),
            new = listOf(candidate("n1", "Chapter 12"), candidate("n2", "Ch. 4.5")),
        )

        assertEquals(listOf("o1" to "n1", "o2" to "n2"), result.pairs())
    }

    @Test
    fun `extras do not match numbered chapters`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "Bonus Chapter 5"),
                candidate("o2", "Author's Note 6"),
                candidate("o3", "Side Story 7"),
                candidate("o4", "[TL] Chapter 8"),
            ),
            new = listOf(
                candidate("n1", "Chapter 5"),
                candidate("n2", "Chapter 6"),
                candidate("n3", "Chapter 7"),
                candidate("n4", "Chapter 8"),
            ),
        )

        assertEquals(listOf("o4" to "n4"), result.pairs())
        assertEquals(listOf("o1", "o2", "o3"), result.unmatchedOld.map { it.key })
        assertEquals(listOf("n1", "n2", "n3"), result.unmatchedNew.map { it.key })
    }

    @Test
    fun `duplicates pair in list order and leftovers are unmatched`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "Chapter 7"),
                candidate("o2", "Chapter 7"),
                candidate("o3", "Chapter 8"),
            ),
            new = listOf(
                candidate("n1", "Chapter 7"),
                candidate("n2", "Chapter 7"),
                candidate("n3", "Chapter 8."),
            ),
        )

        assertEquals(listOf("o1" to "n1", "o2" to "n2", "o3" to "n3"), result.pairs())
        assertTrue(result.unmatchedOld.isEmpty())
        assertTrue(result.unmatchedNew.isEmpty())

        val shortNew = matcher.match(
            old = listOf(candidate("o1", "Chapter 7"), candidate("o2", "Chapter 7")),
            new = listOf(candidate("n1", "Chapter 7")),
        )
        assertEquals(listOf("o1" to "n1"), shortNew.pairs())
        assertEquals(listOf("o2"), shortNew.unmatchedOld.map { it.key })
        assertTrue(shortNew.unmatchedNew.isEmpty())
    }

    @Test
    fun `score is matched old chapters over all old chapters`() {
        val result = matcher.match(
            old = listOf(
                candidate("o1", "Chapter 1"),
                candidate("o2", "Chapter 2"),
                candidate("o3", "Chapter 3"),
            ),
            new = listOf(
                candidate("n1", "Chapter 1"),
                candidate("n2", "Chapter 2"),
                candidate("n3", "Chapter 30"),
            ),
        )

        assertEquals(2f / 3f, result.score)
        assertEquals(2, result.matched.size)
        assertEquals(1, result.unmatchedOld.size)
        assertEquals(1, result.unmatchedNew.size)
        assertEquals(0f, matcher.match(emptyList(), listOf(candidate("n1", "Chapter 1"))).score)
    }

    private fun ChaptersMatchResult.pairs(): List<Pair<String, String>> =
        matched.map { it.old.key to it.new.key }
}
