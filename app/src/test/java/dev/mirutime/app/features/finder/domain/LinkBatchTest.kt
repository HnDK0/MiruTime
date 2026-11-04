package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.library.domain.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkBatchTest {

    @Test
    fun `parse splits newlines and commas trims and dedupes`() {
        val input = "https://a.example/1\nhttps://b.example/2, https://a.example/1\n\n  "
        assertEquals(
            listOf("https://a.example/1", "https://b.example/2"),
            LinkBatch.parse(input),
        )
    }

    @Test
    fun `an invalid link blocks the whole list`() = runTest {
        val preflight = LinkBatch.preflight(
            listOf("https://a.example/1", "junk"),
        ) { url ->
            if (url == "junk") LinkResolution.Invalid else LinkResolution.Found(source(), book())
        }

        assertFalse(preflight.canAdd)
        assertEquals(listOf("junk"), preflight.blocking.map { it.url })
    }

    @Test
    fun `a source error is temporary and does not block`() = runTest {
        val preflight = LinkBatch.preflight(listOf("https://a.example/1")) {
            LinkResolution.SourceError(host = "a.example", sourceName = "A", reason = "cf")
        }

        assertTrue(preflight.canAdd)
        assertEquals(listOf("https://a.example/1"), preflight.addableUrls)
    }

    @Test
    fun `a middle failure does not stop later adds`() = runTest {
        val results = LinkBatch.addAll(
            urls = listOf("a", "b", "c"),
            add = { url -> MembershipResult(success = url != "b") },
        )

        assertEquals(
            listOf(LinkBatch.Status.Added, LinkBatch.Status.Failed, LinkBatch.Status.Added),
            results.map { it.status },
        )
    }

    @Test
    fun `cancel keeps successes and marks the rest unprocessed`() = runTest {
        val collected = mutableListOf<LinkBatch.AddResult>()
        val cancelled = try {
            LinkBatch.addAll(
                urls = listOf("a", "b", "c"),
                add = { url ->
                    if (url == "b") throw CancellationException("stop")
                    MembershipResult(success = true)
                },
                onResult = { collected += it },
            )
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue("cancellation must propagate", cancelled != null)
        assertEquals(
            listOf(
                LinkBatch.Status.Added,
                LinkBatch.Status.Unprocessed,
                LinkBatch.Status.Unprocessed,
            ),
            collected.map { it.status },
        )
    }

    private fun source() = CatalogSource(
        id = "a",
        name = "A",
        language = "en",
        type = ContentType.Novel,
        version = "1",
        pinned = false,
    )

    private fun book() = CatalogBook(
        bookUrl = "https://a.example/1",
        title = "T",
        rating = 0f,
        chapters = 1,
        genres = emptyList(),
        ongoing = false,
    )
}
