package dev.mirutime.core.network.security

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.PluginHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportUrlPolicyTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    // A plain client (ssrfGuard = null) still refuses a non-http scheme.
    private fun client(engine: MockEngine) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    @Test
    fun nonHttpSchemesAreBlocked() {
        for (url in listOf("file:///etc/passwd", "data:text/html,<b>x</b>", "javascript:alert(1)")) {
            val reason = transportViolation(Url(url))
            assertTrue(reason != null && "scheme" in reason, "$url -> $reason")
        }
    }

    @Test
    fun userinfoIsBlocked() {
        assertEquals("credentials in url", transportViolation(Url("https://user:pass@example.com/")))
        assertEquals("credentials in url", transportViolation(Url("http://user@example.com/")))
    }

    // Not an address gate: private/LAN literals and any port are allowed
    // here; the plugin lane's engine validates resolved addresses.
    @Test
    fun httpAndPrivateTargetsWithAnyPortAreAllowed() {
        for (url in listOf(
            "http://example.com/",
            "https://example.com:8443/",
            "http://example.com:8080/path",
            "http://192.168.1.5:8080/api",
            "http://127.0.0.1:9000/",
        )) {
            assertNull(transportViolation(Url(url)), url)
        }
    }

    @Test
    fun plainClientAppliesPolicyWithoutSsrfGuard() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("ok")
        }
        val client = client(engine)

        assertFailsWith<SsrfBlockedException> { client.get("demo", "file:///etc/passwd") }
        assertFailsWith<SsrfBlockedException> { client.get("demo", "javascript:alert(1)") }
        assertFailsWith<SsrfBlockedException> { client.get("demo", "https://user:pass@example.com/") }
        assertEquals(0, engineCalls, "policy blocks must not reach the engine")

        // The same plain client can reach a private/LAN target on any port.
        assertEquals(200, client.get("demo", "http://192.168.1.5:8080/api").status.value)
        assertEquals(1, engineCalls)
    }

    // The policy runs on the raw URL before Ktor's host lookup, so a malformed
    // initial URL surfaces as an SSRF block, not as URLParserException.
    @Test
    fun malformedInitialUrlIsABlockNotAParserThrow() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("ok")
        }

        // A non-numeric port is one of Ktor's URLParserException cases.
        assertFailsWith<SsrfBlockedException> { client(engine).get("demo", "http://example.com:notaport/") }
        assertEquals(0, engineCalls)
    }
}
