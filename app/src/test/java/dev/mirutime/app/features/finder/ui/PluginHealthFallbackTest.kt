package dev.mirutime.app.features.finder.ui

import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The health sheet renders `report.message ?: errorMessage(error)`. A script
// error has no report message and its detail is free text that can quote a
// page, so the fallback must stay empty and the URL/query must never leave the
// builder either.
class PluginHealthFallbackTest {

    @Test
    fun `script detail with a URL never reaches the rendered text`() {
        val detail = "lua:26 bad argument https://site.example/ch/7?token=SECRET"
        val error = PluginError.ScriptError(detail)
        val report = buildPluginErrorReport(
            error = error,
            sourceId = "broken",
            sourceName = "Broken",
            operation = "load",
            identity = "broken",
        )

        assertTrue(pluginErrorBodyIsFreeText(error))
        assertNull(report.message)
        assertFalse(report.detailText, "https://" in report.detailText)
        assertFalse(report.detailText, "SECRET" in report.detailText)
        assertFalse(report.reportText, "https://" in report.reportText)
        assertFalse(report.reportText, "SECRET" in report.reportText)
        assertTrue(report.detailText, "class=ScriptError" in report.detailText)
    }

    @Test
    fun `network cause is free text and only typed fields are formatted`() {
        assertTrue(pluginErrorBodyIsFreeText(PluginError.Network("https://site.example/x?q=1")))
        assertFalse(pluginErrorBodyIsFreeText(PluginError.DnsFailure("site.example")))
        assertFalse(pluginErrorBodyIsFreeText(PluginError.ResponseTooLarge(10)))
    }
}
