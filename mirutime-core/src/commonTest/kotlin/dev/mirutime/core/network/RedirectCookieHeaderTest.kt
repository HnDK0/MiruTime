package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.Cookie
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

// A redirect hop must not carry a caller Cookie across origins, while the
// CookieManager-backed store re-applies each hop for its own URL.
class RedirectCookieHeaderTest {

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

    private fun client(engine: MockEngine, store: CookieStore) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = store,
        logger = RecordingLogger(),
        engine = engine,
    )

    private fun redirecting(to: String): MockEngine = MockEngine { request ->
        if (request.url.encodedPath == "/start") {
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, to),
            )
        } else {
            respond("ok")
        }
    }

    @Test
    fun crossOriginRedirectDropsTheCallerCookieAndAppliesTheStoreForTheNewHost() = runTest {
        val engine = redirecting("https://second.example/final")
        val store = PerHostCookieStore(mapOf("second.example" to "store"))

        client(engine, store).get(
            "demo",
            "https://first.example/start",
            headers = mapOf(HttpHeaders.Cookie to "caller=1"),
        )

        assertEquals("caller=1", engine.requestHistory[0].headers[HttpHeaders.Cookie])
        assertEquals(
            "hop=store",
            engine.requestHistory[1].headers[HttpHeaders.Cookie],
            "the cross-origin hop keeps only the new host's store cookies",
        )
    }

    @Test
    fun sameOriginRedirectKeepsTheCallerCookie() = runTest {
        val engine = redirecting("https://first.example/final")

        client(engine, PerHostCookieStore(emptyMap())).get(
            "demo",
            "https://first.example/start",
            headers = mapOf(HttpHeaders.Cookie to "caller=1"),
        )

        assertEquals("caller=1", engine.requestHistory[0].headers[HttpHeaders.Cookie])
        assertEquals("caller=1", engine.requestHistory[1].headers[HttpHeaders.Cookie])
    }

    @Test
    fun storeCookiesAreReappliedForEachHop() = runTest {
        val engine = redirecting("https://second.example/final")
        val store = PerHostCookieStore(
            mapOf("first.example" to "one", "second.example" to "two"),
        )

        client(engine, store).get("demo", "https://first.example/start")

        assertEquals("hop=one", engine.requestHistory[0].headers[HttpHeaders.Cookie])
        assertEquals("hop=two", engine.requestHistory[1].headers[HttpHeaders.Cookie])
    }

    // One cookie per host, exactly like a domain-scoped CookieManager.
    private class PerHostCookieStore(
        private val values: Map<String, String>,
    ) : CookieStore {
        override suspend fun get(url: String): List<Cookie> {
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
            return values[host]?.let { listOf(Cookie("hop", it, domain = host)) }.orEmpty()
        }

        override suspend fun setFromResponse(url: String, setCookieHeaders: List<String>) = Unit

        override suspend fun clear(host: String) = Unit
    }
}
