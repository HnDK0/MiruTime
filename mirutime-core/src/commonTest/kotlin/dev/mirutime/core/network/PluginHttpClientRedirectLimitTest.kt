package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginHttpClientRedirectLimitTest {

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

    // The default-limit case below builds the client without maxRedirects, so
    // the constructor default (20) is what is actually exercised.
    private fun client(engine: MockEngine, maxRedirects: Int) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        maxRedirects = maxRedirects,
    )

    private fun redirectingEngine(counter: () -> Unit): MockEngine = MockEngine {
        counter()
        respond(
            content = "",
            status = HttpStatusCode.MovedPermanently,
            headers = headersOf(HttpHeaders.Location, "https://example.com/next"),
        )
    }

    @Test
    fun twoRedirectsSendExactlyThreeRequests() = runTest {
        var calls = 0
        // A redirect on the final allowed hop is a typed overflow now.
        val error = assertFailsWith<RedirectLimitException> {
            client(redirectingEngine { calls++ }, maxRedirects = 2)
                .get("demo", "https://example.com/start")
        }

        assertEquals(3, calls, "initial request + 2 followed hops")
        assertEquals("https://example.com/next", error.url)
    }

    @Test
    fun defaultLimitIsTwenty() = runTest {
        var calls = 0
        val error = assertFailsWith<RedirectLimitException> {
            PluginHttpClient(
                metadata = metadata,
                systemInfo = FakeSystemInfo(),
                cookieStore = FakeCookieStore(),
                logger = RecordingLogger(),
                engine = redirectingEngine { calls++ },
            ).get("demo", "https://example.com/start")
        }

        assertEquals(21, calls, "initial request + 20 followed hops")
        assertEquals("https://example.com/next", error.url)
    }
}
