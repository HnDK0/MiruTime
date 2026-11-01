package dev.mirutime.core.network.security

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.PluginHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Transport policy on a plain client (ssrfGuard = null): every redirect target
// is refused before the next request when it is not http(s) or carries
// userinfo. Address rules are NOT applied — private/LAN literals stay reachable
// on any port, which is what the repo/update lane needs.
class PluginHttpClientUrlPolicyTest {

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

    private fun client(engine: MockEngine) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    // A non-http(s) Location is not followed at all: the 3xx comes back as the
    // final response and the hostile target never reaches the engine.
    @Test
    fun nonHttpRedirectIsRefusedBeforeTheNextRequest() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "file:///etc/passwd"),
            )
        }

        val response = client(engine).get("demo", "https://example.com/plugin.lua")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, engineCalls, "the file: target must never be requested")
    }

    @Test
    fun userinfoRedirectIsRefusedBeforeTheNextRequest() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://user:pass@example.com/x"),
            )
        }

        assertFailsWith<SsrfBlockedException> {
            client(engine).get("demo", "https://example.com/plugin.lua")
        }
        assertEquals(1, engineCalls)
    }

    @Test
    fun redirectToPrivateLiteralOnANonDefaultPortIsFollowed() = runTest {
        val target = "http://192.168.1.5:8080/plugin.lua"
        val engine = MockEngine { request ->
            if (request.url.toString() == "https://example.com/plugin.lua") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, target),
                )
            } else {
                respond("return {}")
            }
        }
        val response = client(engine).get("demo", "https://example.com/plugin.lua")

        assertEquals(target, response.request.url.toString())
        assertTrue(response.bodyAsText().contains("return"))
    }
}
