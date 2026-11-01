package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerErrorRetryInterceptorTest {

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

    @Test
    fun transientServerErrorIsRetriedUntilSuccess() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("boom", HttpStatusCode.ServiceUnavailable) else respond("ok")
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals(3, calls)
    }

    // NoveLA parity: a bare 429 is a retryable rate limit (GET-only, bounded).
    @Test
    fun rateLimitIsRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("slow down", HttpStatusCode.TooManyRequests)
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(5, calls, "1 initial attempt + 4 backoff retries")
        assertEquals(7_500L, testScheduler.currentTime, "500+1000+2000+4000ms backoff")
    }

    // A CF-marked 429 is a browser check, not a rate limit: solving must start
    // immediately instead of after ~7.5s of status retries. Without a solver
    // the client fails fast, and the engine must have been called exactly once.
    @Test
    fun cfMitigated429SkipsRetry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "challenge",
                HttpStatusCode.TooManyRequests,
                headersOf(CF_MITIGATED, "challenge"),
            )
        }

        assertFailsWith<CfChallengeRequiredException> {
            client(engine).get("demo", "https://example.com/x")
        }

        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime, "no backoff before the CF solver")
    }

    // "Server: cloudflare" alone is not challenge evidence on a 429 — a bare
    // rate limit behind the CDN still retries (the detector keeps 429 out of
    // the edge-header set).
    @Test
    fun bare429WithCloudflareServerStillRetries() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "slow down",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.Server, "cloudflare"),
            )
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(5, calls)
    }

    @Test
    fun retryAfterHonored() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    "slow down",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter, "2"),
                )
            } else {
                respond("ok")
            }
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
        assertEquals(2_000L, testScheduler.currentTime, "Retry-After: 2 means a 2s delay")
    }

    @Test
    fun retryAfterIsCapped() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    "slow down",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter, "999"),
                )
            } else {
                respond("ok")
            }
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
        assertEquals(MAX_RETRY_AFTER_MS, testScheduler.currentTime, "never sleep unbounded")
    }

    @Test
    fun transientServerErrorsStillRetryFiveAttempts() = runTest {
        for (status in listOf(
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
        )) {
            var calls = 0
            val engine = MockEngine {
                calls++
                respond("boom", status)
            }

            val response = client(engine).get("demo", "https://example.com/x")

            assertEquals(status, response.status)
            assertEquals(5, calls, "1 initial attempt + 4 backoff retries for $status")
        }
    }

    @Test
    fun clientErrorIsNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("nope", HttpStatusCode.NotFound)
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, calls)
    }

    // The counterpart to the CF short-circuit: only a *challenge-shaped* 503 is
    // held back. With 503 removed from CfOptions.statuses this is an ordinary
    // transient error, so the normal backoff must still run to exhaustion.
    @Test
    fun plainServerError503StillRetries() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("boom", HttpStatusCode.ServiceUnavailable)
        }
        val plainClient = PluginHttpClient(
            metadata = metadata.copy(cfOptions = CfOptions(statuses = listOf(403, 429))),
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
        )

        val response = plainClient.get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(5, calls) // 1 initial attempt + 4 backoff retries
    }
}
