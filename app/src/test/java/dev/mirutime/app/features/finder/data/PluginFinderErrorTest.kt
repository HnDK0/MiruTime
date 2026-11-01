package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// A plugin Outcome.Err is a real failure: the finder screen must show its
// error/Retry state, never an empty "Nothing found" page.
class PluginFinderErrorTest {

    private val loginError = PluginError.UserMessage(
        title = "Login required",
        message = "Session expired",
        authUrl = "https://site.example/login",
    )

    private fun thrownBy(operation: String, error: PluginError): PluginRequestException {
        try {
            requirePluginPage(operation, Outcome.Err(error))
            fail("Outcome.Err must throw")
            error("unreachable")
        } catch (thrown: PluginRequestException) {
            return thrown
        }
    }

    @Test
    fun catalogErrorThrowsTheSameTypedError() {
        assertEquals(loginError, thrownBy("catalog", loginError).error)
    }

    @Test
    fun searchErrorThrowsTheSameTypedError() {
        assertEquals(loginError, thrownBy("search", loginError).error)
    }

    @Test
    fun genuinelyEmptySuccessfulPageStaysEmpty() {
        val page = requirePluginPage(
            "catalog",
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false)),
        )

        assertTrue(page.items.isEmpty())
        assertEquals(false, page.hasNext)
    }

    @Test
    fun networkErrorIsNotConvertedToEmpty() {
        val error = PluginError.Network("timeout")
        assertEquals(error, thrownBy("catalogFiltered", error).error)
    }

    // Search results use the same page contract; guard against the old
    // IllegalStateException path losing the typed error fields.
    @Test
    fun userMessageKeepsAuthUrlOnTheException() {
        val thrown = thrownBy("search", loginError)

        val error = thrown.error as PluginError.UserMessage
        assertEquals("https://site.example/login", error.authUrl)
    }

    // The UI maps the exception to the SAME typed title/message/
    // authUrl instead of re-deriving its own text.
    @Test
    fun reportKeepsTheSameTitleMessageAndAuthUrl() {
        val thrown = requirePluginPageNamed("search", loginError)

        val report = thrown.toReport()

        assertEquals("Login required", report.title)
        assertEquals("Session expired", report.message)
        assertEquals("https://site.example/login", report.authUrl)
        assertTrue(report.detailText, "source=Demo (demo)" in report.detailText)
        assertTrue(report.detailText, "op=search" in report.detailText)
    }

    private fun requirePluginPageNamed(
        operation: String,
        error: PluginError,
    ): PluginRequestException {
        try {
            requirePluginPage(operation, Outcome.Err(error), "demo", "Demo")
            error("unreachable")
        } catch (thrown: PluginRequestException) {
            return thrown
        }
    }
}
