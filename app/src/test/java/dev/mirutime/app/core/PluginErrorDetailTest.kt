package dev.mirutime.app.core

import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The detail is the support artifact: what failed, where, and through which
// plugin. It must be stable and technical, while never carrying a query, a
// cookie, a UA or a full URL.
class PluginErrorDetailTest {

    private fun report(
        error: PluginError,
        operation: String = "catalog",
    ) = buildPluginErrorReport(
        error = error,
        sourceId = "freewebnovel",
        sourceName = "FreeWebNovel",
        operation = operation,
        identity = "https://site.example/catalog?q=secret&genre=action",
    )

    @Test
    fun networkWithStatusRendersHttpLine() {
        val report = report(
            PluginError.Network(
                cause = "network",
                status = 403,
                reason = "Forbidden",
                host = "site.example",
                path = "/catalog",
                scheme = "https",
            ),
        )

        assertTrue(report.detailText, "HTTP 403 Forbidden" in report.detailText)
        assertTrue(report.detailText, "request=https://site.example/catalog" in report.detailText)
        assertTrue(
            report.detailText,
            "source=FreeWebNovel (freewebnovel) op=catalog" in report.detailText,
        )
        assertTrue(report.detailText, "exception=Network" in report.detailText)
        assertEquals("PLG-403", report.code)
        assertTrue(report.reportText, "code=PLG-403" in report.reportText)
        assertTrue(report.reportText, "idHash=" in report.reportText)
    }

    @Test
    fun networkWithoutSchemeKeepsHostPath() {
        val report = report(
            PluginError.Network(
                cause = "network",
                status = 403,
                reason = "Forbidden",
                host = "site.example",
                path = "/catalog",
            ),
        )

        assertTrue(report.detailText, "request=site.example/catalog" in report.detailText)
        assertFalse(report.detailText, "https://" in report.detailText)
    }

    @Test
    fun networkWithoutStatusRendersClass() {
        val report = report(PluginError.Network("request failed"))

        assertTrue(report.detailText, "class=Network" in report.detailText)
        assertEquals("PLG-NET", report.code)
        assertFalse(report.detailText, "HTTP " in report.detailText)
    }

    @Test
    fun timeoutClassAndCode() {
        val report = report(PluginError.Network("Timeout", host = "site.example", path = "/a"))

        assertTrue(report.detailText, "class=Timeout" in report.detailText)
        assertTrue(report.detailText, "request=site.example/a" in report.detailText)
        assertEquals("PLG-TIMEOUT", report.code)
    }

    @Test
    fun scriptErrorMapsToScriptClass() {
        val report = report(PluginError.ScriptError("getChapterList returned nil"))

        assertTrue(report.detailText, "class=ScriptError" in report.detailText)
        assertTrue(
            report.detailText,
            "exception=ScriptError detail=getChapterList returned nil" in report.detailText,
        )
        assertEquals("PLG-SCRIPT", report.code)
    }

    @Test
    fun scriptErrorSafeDetailIsExcerpted() {
        val report = report(PluginError.ScriptError("parsePage failed on page 2"))

        assertTrue(
            report.detailText,
            "exception=ScriptError detail=parsePage failed on page 2" in report.detailText,
        )
    }

    @Test
    fun scriptErrorUnsafeDetailIsRedacted() {
        val report = report(PluginError.ScriptError("<html>secret?x=1</html>"))

        assertTrue(report.detailText, "exception=ScriptError detail=redacted" in report.detailText)
        assertFalse(report.detailText, "secret" in report.detailText)
        assertFalse(report.detailText, "<html>" in report.detailText)
    }

    @Test
    fun scriptErrorLongDetailIsRedacted() {
        val report = report(PluginError.ScriptError("x".repeat(121)))

        assertTrue(report.detailText, "detail=redacted" in report.detailText)
        assertFalse(report.detailText, "x".repeat(121) in report.detailText)
    }

    @Test
    fun sourceIdOnlyFallsBackToId() {
        val report = buildPluginErrorReport(
            error = PluginError.Network("request failed"),
            sourceId = "freewebnovel",
            operation = "catalog",
        )

        assertTrue(report.detailText, "source=freewebnovel op=catalog" in report.detailText)
        assertFalse(report.detailText, "freewebnovel (" in report.detailText)
    }

    @Test
    fun ssrfAndLargeMapToTheirOwnCodes() {
        val ssrf = report(PluginError.Network("SsrfBlocked", host = "127.0.0.1", path = "/x"))
        assertTrue(ssrf.detailText, "class=SsrfBlocked" in ssrf.detailText)
        assertEquals("PLG-SSRF", ssrf.code)

        val large = report(PluginError.ResponseTooLarge(25_000_000))
        assertTrue(large.detailText, "class=ResponseTooLarge" in large.detailText)
        assertEquals("PLG-BIG", large.code)
    }

    @Test
    fun userMessageKeepsTitleAndMessageVerbatim() {
        val report = report(
            PluginError.UserMessage(
                title = "Login required",
                message = "Session expired",
                authUrl = "https://site.example/login?token=secret",
            ),
        )

        assertTrue(report.detailText, "Login required" in report.detailText)
        assertTrue(report.detailText, "Session expired" in report.detailText)
        assertEquals("Login required", report.title)
        assertEquals("Session expired", report.message)
        assertEquals("https://site.example/login?token=secret", report.authUrl)
        assertEquals("PLG-PLUGIN", report.code)
        assertTrue(report.detailText, "exception=UserMessage" in report.detailText)
        assertFalse(report.detailText, "token=secret" in report.detailText)
        assertFalse(report.detailText, "https://site.example/login" in report.detailText)
    }

    @Test
    fun cfChallengeNamesTheHost() {
        val report = report(PluginError.CfChallengeRequired("site.example"))

        assertEquals("Cloudflare challenge on site.example", report.detailText.lineSequence()
            .first { it.startsWith("Cloudflare challenge") })
        assertEquals("PLG-CF", report.code)
        assertEquals("site.example", report.cfHost)
    }

    @Test
    fun dnsFailureIsANetworkFailure() {
        val report = report(PluginError.DnsFailure("dead.example"))

        assertTrue(report.detailText, "class=DnsFailure" in report.detailText)
        assertTrue(report.detailText, "request=dead.example" in report.detailText)
        assertEquals("PLG-NET", report.code)
    }

    @Test
    fun fallbackWithoutTypedErrorIsStillReadable() {
        val report = buildPluginErrorReport(
            error = null,
            sourceId = "shuba",
            sourceName = "69shuba",
            operation = "chapters",
            fallbackMessage = "The source did not answer",
        )

        assertTrue(report.detailText, "source=69shuba" in report.detailText)
        assertTrue(report.detailText, "op=chapters" in report.detailText)
        assertTrue(report.detailText, "The source did not answer" in report.detailText)
        assertEquals("PLG-PLUGIN", report.code)
        assertNull(report.authUrl)
    }
}
