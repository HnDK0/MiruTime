package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.core.network.security.SsrfGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// Redirect overflow used to return the last 3xx as a successful response, so a
// redirect loop looked like "no body" instead of a typed network failure.
class RedirectLimitTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "en",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun client(
        engine: MockEngine,
        logger: RecordingLogger = RecordingLogger(),
        ssrfGuard: SsrfGuard? = null,
        maxRedirects: Int = MAX_REDIRECTS,
    ) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = logger,
        engine = engine,
        ssrfGuard = ssrfGuard,
        maxRedirects = maxRedirects,
    )

    @Test
    fun redirectOverflowThrowsTypedFailure() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "/r$calls"),
            )
        }

        val error = assertFailsWith<RedirectLimitException> {
            client(engine).get("demo", "https://example.com/start")
        }

        assertEquals(21, calls)
        assertEquals("https://example.com/r20", error.url)
    }

    @Test
    fun jsRedirectOverflowThrowsTypedFailure() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "<html><script>window.location.href='/n$calls'</script></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }

        val error = assertFailsWith<RedirectLimitException> {
            client(engine).get("demo", "https://example.com/start")
        }

        assertEquals(21, calls)
        assertEquals("https://example.com/n20", error.url)
    }

    @Test
    fun ssrfTargetAtHopCapSurfacesAsSsrfNotRedirectLimit() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://example.com/r2"),
                )
            } else {
                // Same hop the cap would refuse: the private address is the
                // reason, so blocked=ssrf must win over blocked=max.
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "http://127.0.0.1/secret"),
                )
            }
        }

        assertFailsWith<SsrfBlockedException> {
            client(engine, logger, ssrfGuard = SsrfGuard(), maxRedirects = 1)
                .get("demo", "https://example.com/start")
        }

        assertEquals(2, calls)
        val redirects = logger.lines.filter { "event=redirect" in it }
        assertTrue(redirects.any { "blocked=ssrf" in it }, redirects.joinToString("\n"))
        assertTrue(redirects.none { "blocked=max" in it }, redirects.joinToString("\n"))
    }
}
