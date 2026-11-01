package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression pin for the replay that already exists in PluginHttpClient:
// after a solve, the retry is the SAME request — method, body, and the
// solver's UA/cookies — not a bare GET. The pre-solve cookies must survive.
class PluginHttpClientCfPostReplayTest {

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

    private class FakeSolver(private val result: CfSolution?) : CfChallengeSolver {
        var calls = 0
        override suspend fun solve(request: CfChallenge): CfSolution? {
            calls++
            return result
        }
    }

    @Test
    fun postRetryAfterSolveKeepsMethodBodyUaAndCookies() = runTest {
        val cookieStore = FakeCookieStore()
        cookieStore.setFromResponse("https://example.com/", listOf("session=keep; Path=/"))
        var calls = 0
        var retryMethod: HttpMethod? = null
        var retryBody: String? = null
        var retryUserAgent: String? = null
        var retryCookie: String? = null
        val engine = MockEngine { request ->
            calls++
            if (calls == 1) {
                respond(
                    "<html>Just a moment...<div id=\"challenge-running\"></div></html>",
                    HttpStatusCode.Forbidden,
                )
            } else {
                retryMethod = request.method
                retryBody = (request.body as? TextContent)?.text
                retryUserAgent = request.headers[HttpHeaders.UserAgent]
                retryCookie = request.headers[HttpHeaders.Cookie]
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), "SolverUA/7.7"))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = cookieStore,
            logger = RecordingLogger(),
            engine = engine,
            solver = solver,
        )

        val response = client.post(
            "demo",
            "https://example.com/search",
            body = "q=test",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, solver.calls)
        assertEquals(HttpMethod.Post, retryMethod)
        assertEquals("q=test", retryBody)
        assertEquals("SolverUA/7.7", retryUserAgent)
        val cookie = retryCookie.orEmpty()
        assertTrue(cookie.contains("session=keep"), "pre-solve cookies must be replayed: $cookie")
        assertTrue(cookie.contains("cf_clearance=token-1"), "solver clearance must be attached: $cookie")
    }
}
