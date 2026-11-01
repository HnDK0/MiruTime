package dev.mirutime.core.network

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Hard gate for the debug sink: the formatter may describe a request, never
// reproduce it. Every value that could name a user, a session or a page is
// absent; header NAMES, cookie domains and cf_clearance presence are all a
// reader needs.
class DebugLogRedactionTest {

    private val event = NetworkTraceEvent(
        method = "GET",
        url = "https://host.example/path?q=secret-query",
        status = 200,
        requestHeaders = mapOf(
            HttpHeaders.Cookie to "cf_clearance=SECRET-CLEARANCE; sid=SECRET-SID",
            HttpHeaders.Authorization to "Bearer SECRET-TOKEN",
            HttpHeaders.UserAgent to "SecretUA/1.0",
            "Referer" to "https://host.example/from?q=secret-query",
            HttpHeaders.Accept to "text/html",
            "X-Requested-With" to "XMLHttpRequest",
        ),
        responseHeaders = mapOf("set-cookie" to listOf("sid=SECRET-SID"), "server" to listOf("cloudflare")),
        bodyBytes = 123,
        durationMs = 45,
        cfBlocked = false,
        redirectHop = 0,
        plugin = "getCatalogFiltered",
        args = "genre_included,_query",
        cookiesStored = 3,
        cookiesApplied = 3,
        cfClearance = true,
    )

    @Test
    fun formatterKeepsShapeAndDropsSecrets() {
        val line = formatNetworkTrace(event)

        // Safe facts.
        assertTrue("event=http" in line, line)
        assertTrue("method=GET" in line, line)
        assertTrue("url=https://host.example/path" in line, line)
        assertTrue("status=200" in line, line)
        assertTrue("bytes=123" in line, line)
        assertTrue("elapsed=45ms" in line, line)
        assertTrue("plugin=getCatalogFiltered" in line, line)
        assertTrue("args=genre_included,_query" in line, line)
        assertTrue("cookies=3" in line, line)
        assertTrue("applies=3/3" in line, line)
        assertTrue("cf_clearance=present" in line, line)
        assertFalse("cookieDomains" in line, line)
        assertTrue("cookie-names=cf_clearance,sid" in line, line)
        assertTrue("ua=present" in line, line)
        assertTrue("authorization=redacted" in line, line)
        assertTrue("referer=https://host.example/from" in line, line)
        assertTrue("x-requested-with=XMLHttpRequest" in line, line)

        // Never.
        assertFalse("SECRET" in line, line)
        assertFalse("secret-query" in line, line)
        assertFalse("?" in line, line)
        assertFalse("Cookie:" in line, line)
        assertFalse("Authorization:" in line, line)
        assertFalse("SecretUA" in line, line)
        assertFalse("sid=SECRET-SID" in line, line)
    }

    @Test
    fun exampleNetworkLine() {
        val line = formatNetworkTrace(event)
        println("EXAMPLE-NETWORK $line")
        assertTrue("plugin=getCatalogFiltered" in line, line)
    }

    @Test
    fun eventWithoutSensitiveHeadersStaysQuiet() {
        val clean = event.copy(
            url = "https://host.example/plain",
            requestHeaders = mapOf(HttpHeaders.Accept to "text/html"),
            responseHeaders = emptyMap(),
        )

        val line = formatNetworkTrace(clean)

        assertFalse("cookie-names" in line, line)
        assertFalse("authorization" in line, line)
        assertFalse("ua=" in line, line)
        assertFalse("referer" in line, line)
    }

    @Test
    fun cfTriggerIsADecisionNameOnly() {
        val line = formatNetworkTrace(event.copy(cfBlocked = true, cfTrigger = "cf-mitigated"))

        assertTrue("cfBlocked=true" in line, line)
        assertTrue("cfTrigger=cf-mitigated" in line, line)
    }

    @Test
    fun relativePathNeverLeaksUserinfo() {
        assertEquals(
            "host.example/path",
            safeHostPath("user:pass@host.example/path?q=secret-query"),
        )
    }
}
