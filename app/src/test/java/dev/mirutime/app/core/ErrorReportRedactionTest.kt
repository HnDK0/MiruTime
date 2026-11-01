package dev.mirutime.app.core

import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Copy and Share both use reportText verbatim: the preview, the clipboard and
// the chooser payload can never disagree, and none of them may carry a query,
// a full URL, a cookie, an Authorization value or a UA value.
class ErrorReportRedactionTest {

    private val secretMessage = """
        Session expired at https://site.example/login?token=SECRET-TOKEN&next=1
        Cookie: sid=SECRET-SID; cf_clearance=SECRET-CLEARANCE
        Authorization: Bearer SECRET-BEARER
        User-Agent: SecretUA/1.0
    """.trimIndent()

    private val report = buildPluginErrorReport(
        error = PluginError.UserMessage(
            title = "Login required",
            message = secretMessage,
            authUrl = "https://site.example/login?token=SECRET-TOKEN",
        ),
        sourceId = "freewebnovel",
        sourceName = "FreeWebNovel",
        operation = "content",
        identity = "https://site.example/chapter/7?token=SECRET-TOKEN",
    )

    @Test
    fun reportDropsEverySecretAndQuery() {
        val payload = report.reportText

        assertFalse(payload, "SECRET" in payload)
        assertFalse(payload, "?" in payload)
        assertFalse(payload, "https://site.example/login" in payload)
        assertFalse(payload, "Cookie:" in payload)
        assertFalse(payload, "Authorization:" in payload)
        assertFalse(payload, "SecretUA" in payload)
        assertTrue(payload, "code=PLG-PLUGIN" in payload)
        assertTrue(payload, "idHash=" in payload)
        assertTrue(payload, "Login required" in payload)
        assertTrue(payload, "op=content" in payload)
    }

    @Test
    fun detailIsTruncatedTo2048() {
        val long = buildPluginErrorReport(
            error = PluginError.UserMessage(
                title = "Big",
                message = "x".repeat(5000),
            ),
            sourceId = "src",
            operation = "catalog",
        )

        assertTrue("detail=${long.detailText.length}", long.detailText.length <= 2048)
        // The code line is appended AFTER truncation, so it is never cut.
        assertTrue(
            long.reportText.takeLast(80),
            long.reportText.trim().matches(
                Regex(".*\\ncode=PLG-PLUGIN idHash=(?:[0-9a-f]+|-)$", RegexOption.DOT_MATCHES_ALL),
            ),
        )
    }

    @Test
    fun scriptDetailNeverCarriesUrlQueryOrHeader() {
        val scriptReport = buildPluginErrorReport(
            error = PluginError.ScriptError(
                "Cookie: sid=SECRET-SID https://site.example/x?token=SECRET-TOKEN",
            ),
            sourceId = "freewebnovel",
            sourceName = "FreeWebNovel",
            operation = "content",
            identity = "https://site.example/x?token=SECRET-TOKEN",
        )

        listOf(
            scriptReport.detailText,
            scriptReport.reportText,
            scriptReport.copyPayload(),
            scriptReport.sharePayload(),
        ).forEach { payload ->
            assertFalse(payload, "SECRET" in payload)
            assertFalse(payload, "https://" in payload)
            assertFalse(payload, "?" in payload)
        }
        assertTrue(scriptReport.detailText, "detail=redacted" in scriptReport.detailText)
    }

    @Test
    fun copyPayloadEqualsSharePayloadEqualsReportText() {
        // Both actions read this one value; the UI passes it through unchanged.
        assertEquals(report.reportText, report.copyPayload())
        assertEquals(report.reportText, report.sharePayload())
    }

    @Test
    fun authUrlStaysOutOfThePayload() {
        // The raw address remains available for the Open-login action, but
        // never reaches the rendered/copied/shared text.
        assertEquals("https://site.example/login?token=SECRET-TOKEN", report.authUrl)
        assertFalse(report.reportText, "https://site.example/login" in report.reportText)
        assertFalse(report.reportText, "SECRET" in report.reportText)
    }
}
