package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfBudget
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The bridge (NetworkBindings) caps every plugin call at 30s. The hidden
// auto-solve must fit INSIDE that budget (15s), and the visible screen (35s)
// must only ever be started by the user, never by this client.
class PluginHttpClientCfBudgetTest {

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

    // A budgeted call must make exactly one status attempt and never open a
    // solver window for the origin's own bare 429: the status ladder owns it.
    // (PluginHttpClientResilienceTest.budgetedCallMakesOneStatusAttemptOn429
    // covers the attempt count without a solver configured.)
    // runBlocking, not runTest: a pending withTimeout under runTest lets the
    // virtual scheduler expire the budget before the engine ever runs.
    @Test
    fun budgeted429MakesOneAttemptWithoutSolver() = runBlocking {
        var engineCalls = 0
        var solverCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond(
                "slow down",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "2"),
            )
        }
        val solver = object : CfChallengeSolver {
            override suspend fun solve(request: CfChallenge): CfSolution? {
                solverCalls++
                return CfSolution(mapOf("cf_clearance" to "late"), null)
            }
        }
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/page", timeoutMs = 30_000)

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(1, engineCalls)
        assertEquals(0, solverCalls)
    }

    // A solver that ignores its own bound must not outlive the caller's budget:
    // the whole call (including the solve) ends with HttpRequestTimeoutException.
    @Test
    fun solverPastCallBudgetFailsWithTimeout() = runTest {
        val engine = MockEngine {
            respond(
                "<html>Just a moment...<div id=\"challenge-running\"></div></html>",
                HttpStatusCode.Forbidden,
            )
        }
        val solver = object : CfChallengeSolver {
            override suspend fun solve(request: CfChallenge): CfSolution? {
                delay(CfBudget.VISIBLE_MS)
                return CfSolution(mapOf("cf_clearance" to "late"), null)
            }
        }
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
            solver = solver,
        )

        assertFailsWith<HttpRequestTimeoutException> {
            client.get("demo", "https://example.com/page", timeoutMs = 30_000)
        }
    }
}
