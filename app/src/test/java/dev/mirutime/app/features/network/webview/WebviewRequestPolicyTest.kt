package dev.mirutime.app.features.network.webview

import dev.mirutime.core.network.security.SsrfVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

// The hidden WebView has no SafeDns hook, so this decision IS the security
// property: https only, private/LAN addresses blocked, and subresources
// (iframe/worker/XHR/img) held to exactly the same rule as the main frame.
class WebviewRequestPolicyTest {

    private fun decide(
        scheme: String?,
        verdict: SsrfVerdict?,
        mainFrame: Boolean = true,
    ): WebviewRequestDecision = webviewRequestDecision(scheme, verdict, mainFrame)

    @Test
    fun httpsPublicIsAllowed() {
        assertEquals(WebviewRequestDecision.Allow, decide("https", SsrfVerdict.Ok))
    }

    @Test
    fun httpsPrivateIsBlocked() {
        val verdict = SsrfVerdict.Blocked("resolved 10.0.0.7: 10.0.0.0/8 (private)")
        assertEquals(WebviewRequestDecision.Block, decide("https", verdict))
    }

    @Test
    fun httpsLanAndLoopbackAreBlocked() {
        assertEquals(
            WebviewRequestDecision.Block,
            decide("https", SsrfVerdict.Blocked("resolved 192.168.1.5: 192.168.0.0/16 (private)")),
        )
        assertEquals(
            WebviewRequestDecision.Block,
            decide("https", SsrfVerdict.Blocked("127.0.0.0/8 (loopback)")),
        )
        assertEquals(
            WebviewRequestDecision.Block,
            decide("https", SsrfVerdict.Blocked("169.254.0.0/16 (link-local/metadata)")),
        )
    }

    @Test
    fun httpsDnsFailureIsBlocked() {
        assertEquals(WebviewRequestDecision.Block, decide("https", SsrfVerdict.DnsFailure))
    }

    @Test
    fun nonHttpsIsBlockedEvenWithAPublicAddress() {
        assertEquals(WebviewRequestDecision.Block, decide("http", SsrfVerdict.Ok))
        assertEquals(WebviewRequestDecision.Block, decide("file", SsrfVerdict.Ok))
        assertEquals(WebviewRequestDecision.Block, decide("data", SsrfVerdict.Ok))
        assertEquals(WebviewRequestDecision.Block, decide(null, SsrfVerdict.Ok))
    }

    @Test
    fun schemeCheckIsCaseInsensitive() {
        assertEquals(WebviewRequestDecision.Allow, decide("HTTPS", SsrfVerdict.Ok))
    }

    @Test
    fun deferredAddressCheckStillEnforcesHttps() {
        // shouldOverrideUrlLoading (Main) passes no verdict — DNS is not
        // allowed there — but the scheme rule still applies.
        assertEquals(WebviewRequestDecision.Allow, decide("https", verdict = null))
        assertEquals(WebviewRequestDecision.Block, decide("http", verdict = null))
    }

    @Test
    fun subresourceGetsTheSameDecisionAsTheMainFrame() {
        val verdicts = listOf(
            SsrfVerdict.Ok,
            SsrfVerdict.DnsFailure,
            SsrfVerdict.Blocked("127.0.0.0/8 (loopback)"),
        )
        for (verdict in verdicts) {
            for (scheme in listOf("https", "http", null)) {
                assertEquals(
                    "main frame and subresource must share one rule for $scheme/$verdict",
                    decide(scheme, verdict, mainFrame = true),
                    decide(scheme, verdict, mainFrame = false),
                )
            }
        }
    }
}
