package dev.mirutime.app.features.finder.ui

import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The global search failure surface uses the SAME shared report
// builder as SourceCatalog, so a typed source error names source+operation,
// HTTP code/reason or class, and request=host+path (never a query).
class GlobalSearchFailureTest {

    private val source = CatalogSource(
        id = "demo",
        name = "Demo Source",
        language = "EN",
        type = ContentType.Novel,
        version = "1.0",
        pinned = false,
    )

    @Test
    fun networkFailureMapsToHttpCodeAndHostPath() {
        val error = PluginRequestException(
            PluginError.Network(
                cause = "Server error",
                status = 503,
                reason = "Service Unavailable",
                host = "plugins.example",
                path = "/api/search",
            ),
            sourceId = "demo",
            operation = "search",
        )

        val report = globalSearchFailureReport(source, error)

        assertTrue(report != null)
        assertEquals("PLG-503", report!!.code)
        assertTrue(report.detailText, "source=Demo Source (demo) op=search" in report.detailText)
        assertTrue(report.detailText, "HTTP 503 Service Unavailable" in report.detailText)
        assertTrue(report.detailText, "request=plugins.example/api/search" in report.detailText)
        assertFalse(report.reportText, "q=" in report.reportText)
    }

    @Test
    fun pluginShowErrorKeepsTitleMessageAndAuthUrl() {
        val error = PluginRequestException(
            PluginError.UserMessage(
                title = "Login required",
                message = "Session expired",
                authUrl = "https://site.example/login?token=SECRET",
            ),
            sourceId = "demo",
            operation = "search",
        )

        val report = globalSearchFailureReport(source, error)!!

        assertEquals("Login required", report.title)
        assertEquals("Session expired", report.message)
        assertEquals("https://site.example/login?token=SECRET", report.authUrl)
        assertFalse(report.reportText, "SECRET" in report.reportText)
        assertTrue(report.detailText, "op=search" in report.detailText)
    }

    @Test
    fun sourceNameFallsBackWhenTheExceptionHasNone() {
        val error = PluginRequestException(
            PluginError.ScriptError("broken"),
            sourceId = "demo",
            operation = "search",
        )

        val report = globalSearchFailureReport(source, error)!!

        assertTrue(report.detailText, "source=Demo Source (demo)" in report.detailText)
        assertTrue(report.detailText, "class=ScriptError" in report.detailText)
    }

    @Test
    fun exceptionSourceNameWinsWithTheIdInParentheses() {
        val error = PluginRequestException(
            PluginError.ScriptError("broken"),
            sourceId = "demo",
            sourceName = "Demo Plugin",
            operation = "search",
        )

        val report = globalSearchFailureReport(source, error)!!

        assertTrue(report.detailText, "source=Demo Plugin (demo) op=search" in report.detailText)
    }

    @Test
    fun untypedFailureHasNoTypedReport() {
        assertNull(globalSearchFailureReport(source, IllegalStateException("boom")))
    }
}
