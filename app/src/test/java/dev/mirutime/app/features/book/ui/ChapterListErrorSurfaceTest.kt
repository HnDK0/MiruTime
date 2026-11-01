package dev.mirutime.app.features.book.ui

import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Chapter surface: a refresh failure is typed (op=chapters) and is
// never logged or shown as a success; stored rows stay, an empty store shows
// the error instead of "No chapters", and only a successful empty answer is
// the real empty state.
class ChapterListErrorSurfaceTest {

    private val stored = listOf(chapter(1), chapter(2))
    private val scriptError = PluginError.ScriptError("parsePage failed on page 2")

    @Test
    fun `refresh failure keeps stored rows and exposes the typed error`() {
        val view = chapterRefreshView(stored, Outcome.Err(scriptError))

        assertEquals(stored, view.rows)
        assertFalse(view.ok)
        assertEquals("ScriptError", view.errorClass)

        val report = buildPluginErrorReport(
            error = view.failure,
            sourceId = "freewebnovel",
            operation = "chapters",
            identity = "https://freewebnovel.com/book/1",
        )
        assertTrue(report.detailText.contains("source=freewebnovel op=chapters"))
        assertTrue(report.detailText.contains("class=ScriptError"))
        // Stored rows stay on screen next to the error card.
        assertTrue(showChaptersSection(view.rows, report))
    }

    @Test
    fun `refresh failure with no rows is a typed error, not the empty state`() {
        val view = chapterRefreshView(emptyList(), Outcome.Err(scriptError))

        assertTrue(view.rows.isEmpty())
        assertFalse(view.ok)
        assertTrue(view.failure is PluginError.ScriptError)
        val report = buildPluginErrorReport(
            error = view.failure,
            sourceId = "freewebnovel",
            operation = "chapters",
        )
        assertFalse(showChaptersSection(view.rows, report))
    }

    @Test
    fun `successful empty list is the only empty state`() {
        val view = chapterRefreshView(stored, Outcome.Ok(emptyList()))

        assertTrue(view.rows.isEmpty())
        assertTrue(view.ok)
        assertNull(view.failure)
        assertTrue(showChaptersSection(view.rows, failure = null))
    }

    @Test
    fun `successful refresh replaces the stored rows`() {
        val fresh = listOf(chapter(3))
        val view = chapterRefreshView(stored, Outcome.Ok(fresh))

        assertEquals(fresh, view.rows)
        assertTrue(view.ok)
        assertNull(view.failure)
    }

    @Test
    fun `network failure logs the HTTP code and reports host path`() {
        val network = PluginError.Network(
            cause = "status",
            status = 503,
            host = "freewebnovel.com",
            path = "/book/1",
        )
        val view = chapterRefreshView(stored, Outcome.Err(network))

        assertEquals("HTTP 503", view.errorClass)
        assertEquals(stored, view.rows)

        val report = buildPluginErrorReport(
            error = network,
            sourceId = "freewebnovel",
            operation = "chapters",
        )
        assertTrue(report.detailText.contains("HTTP 503"))
        assertTrue(report.detailText.contains("request=freewebnovel.com/book/1"))
    }

    // The initial load shares the refresh view: a failed source with no rows
    // renders the error card, and only an Ok view may emit the success
    // event=chapters log. A successful empty answer is the real empty state.
    @Test
    fun `initial load failure with no rows is a typed error, not the empty state`() = runTest {
        var remoteCalls = 0
        val result = loadInitialChapters(
            bookUrl = "https://freewebnovel.com/book/1",
            cached = { null },
            remote = {
                remoteCalls++
                Outcome.Err(scriptError)
            },
        )

        val view = chapterRefreshView(emptyList(), result)
        assertEquals(1, remoteCalls)
        assertFalse(view.ok)
        assertTrue(view.rows.isEmpty())
        assertTrue(view.failure is PluginError.ScriptError)
        val report = buildPluginErrorReport(
            error = view.failure,
            sourceId = "freewebnovel",
            operation = "chapters",
        )
        assertFalse(showChaptersSection(view.rows, report))
    }

    @Test
    fun `initial load successful empty list is the empty state`() = runTest {
        val result = loadInitialChapters(
            bookUrl = "https://freewebnovel.com/book/1",
            cached = { null },
            remote = { Outcome.Ok(emptyList()) },
        )

        val view = chapterRefreshView(emptyList(), result)
        assertTrue(view.ok)
        assertTrue(view.rows.isEmpty())
        assertNull(view.failure)
        assertTrue(showChaptersSection(view.rows, failure = null))
    }

    @Test
    fun `initial load with stored rows returns Ok without the source`() = runTest {
        val result = loadInitialChapters(
            bookUrl = "https://freewebnovel.com/book/1",
            cached = { stored },
            remote = { throw AssertionError("stored rows must not reach the source") },
        )

        assertEquals(Outcome.Ok(stored), result)
    }

    private fun chapter(index: Int) = BookChapter(
        index = index,
        title = "Stored $index",
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    )
}
