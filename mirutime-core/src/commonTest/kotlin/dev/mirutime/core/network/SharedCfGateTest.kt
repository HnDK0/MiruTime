package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfGate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// One CfGate per process: a cooldown entered by one client must fast-fail the
// next client that hits the same normalized host, without a second solve.
class SharedCfGateTest {

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

    private fun client(engine: MockEngine, gate: CfGate, solver: CfChallengeSolver) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        gate = gate,
        solver = solver,
    )

    @Test
    fun sharedGateSharesFailureCooldownAcrossClients() = runTest {
        val gate = CfGate()
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("challenge", HttpStatusCode.Forbidden, headersOf(CF_MITIGATED, "challenge"))
        }
        // A's solver returns null once: the gate enters the failure cooldown.
        val solver = FakeSolver(null)
        val clientA = client(engine, gate, solver)
        val clientB = client(engine, gate, solver)

        assertFailsWith<CfChallengeRequiredException> {
            clientA.get("demo", "https://example.com/page-a")
        }
        assertTrue(gate.isOnCooldown("example.com"), "A's failed solve must cool the shared host down")
        assertEquals(1, engineCalls, "A sends its challenge request only")
        assertEquals(1, solver.calls, "A's solver runs once and gives up")

        // B fetches its OWN challenge (engine call 2), then fast-fails on A's
        // cooldown before its solver can run.
        assertFailsWith<CfChallengeRequiredException> {
            clientB.get("demo", "https://example.com/page-b")
        }
        assertEquals(2, engineCalls)
        assertEquals(1, solver.calls, "the shared cooldown must prevent a second solve")
        assertTrue(gate.isOnCooldown("example.com"))

        // Same instance, not a fresh per-client gate: a second gate has no
        // cooldown of its own.
        assertTrue(!CfGate().isOnCooldown("example.com"))
    }
}
