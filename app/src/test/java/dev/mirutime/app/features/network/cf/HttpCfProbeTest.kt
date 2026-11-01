package dev.mirutime.app.features.network.cf

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCfProbeTest {

    private val target = "https://example.com/book/1"

    private fun probe(http: ProbeHttp) = HttpCfProbe(http = http)

    @Test
    fun `clean response is clear and sends cookies with the browser UA`() = runTestProbe {
        val http = FakeHttp(target to ProbeResponse(200, "<html>chapter</html>"))
        val clear = probe(http).isClear(
            target,
            mapOf("cf_clearance" to "abc", "locale" to "en"),
            "WebViewUA/1",
        )

        assertTrue(clear)
        val headers = http.requests.single().second
        assertEquals("cf_clearance=abc; locale=en", headers["Cookie"])
        assertEquals("WebViewUA/1", headers["User-Agent"])
    }

    @Test
    fun `cloudflare marker on 403 is a challenge`() = runTestProbe {
        val http = FakeHttp(
            target to ProbeResponse(
                status = 403,
                body = "<title>Just a moment...</title><div id=\"cf-challenge-running\"></div>",
                serverHeader = "cloudflare",
            ),
        )
        assertFalse(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
    }

    @Test
    fun `cf-mitigated header marks a challenge without body markers`() = runTestProbe {
        val http = FakeHttp(
            target to ProbeResponse(200, "<html>ok</html>", cfMitigatedHeader = "challenge"),
        )
        assertFalse(probe(http).isClear(target, emptyMap(), "UA"))
    }

    @Test
    fun `redirect to a clean page follows the hop`() = runTestProbe {
        val moved = "https://example.com/moved"
        val http = FakeHttp(
            target to ProbeResponse(302, "", location = moved),
            moved to ProbeResponse(200, "<html>book</html>"),
        )

        assertTrue(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
        assertEquals(listOf(target, moved), http.requests.map { it.first })
    }

    @Test
    fun `redirect into a private address is probed`() = runTestProbe {
        val private = "http://192.168.1.10:8080/book"
        val http = FakeHttp(
            target to ProbeResponse(302, "", location = private),
            private to ProbeResponse(200, "<html>book</html>"),
        )

        assertTrue(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
        assertEquals(listOf(target, private), http.requests.map { it.first })
    }

    @Test
    fun `non-http redirect is rejected`() = runTestProbe {
        val http = FakeHttp(target to ProbeResponse(302, "", location = "file:///etc/passwd"))

        assertFalse(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `cross-host redirect drops cookies`() = runTestProbe {
        val moved = "https://other.example/moved"
        val http = FakeHttp(
            target to ProbeResponse(302, "", location = moved),
            moved to ProbeResponse(200, "<html>book</html>"),
        )

        assertTrue(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
        assertEquals("cf_clearance=abc", http.requests.first().second["Cookie"])
        assertNull(http.requests[1].second["Cookie"])
    }

    @Test
    fun `transport failure is not a clearance`() = runTestProbe {
        val http = FakeHttp()
        assertFalse(probe(http).isClear(target, mapOf("cf_clearance" to "abc"), "UA"))
    }

    // No real network: the fake transport either answers or throws.
    private fun runTestProbe(block: suspend () -> Unit) {
        kotlinx.coroutines.test.runTest { block() }
    }

    private class FakeHttp(vararg routes: Pair<String, ProbeResponse>) : ProbeHttp {
        private val responses = routes.toMap()
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): ProbeResponse {
            requests += url to headers
            return responses[url] ?: throw IOException("no route for $url")
        }
    }
}
