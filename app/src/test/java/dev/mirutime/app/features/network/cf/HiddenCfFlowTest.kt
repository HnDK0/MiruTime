package dev.mirutime.app.features.network.cf

import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Hidden auto-solve is the default and never opens a screen; the visible phase
// exists only for the user-initiated Solve path; a bare 429 is never solved
// automatically (it is the site's own rate limit, not a browser check).
class HiddenCfFlowTest {

    private class FakeSolver(
        private val result: CfSolution? = null,
        private val fail: Boolean = false,
    ) : CfChallengeSolver {
        var calls = 0
        val requests = mutableListOf<CfChallenge>()

        override suspend fun solve(request: CfChallenge): CfSolution? {
            calls++
            requests += request
            if (fail) throw IllegalStateException("boom")
            return result
        }
    }

    private fun challenge(status: Int = 403) = CfChallenge(
        host = "example.com",
        url = "https://example.com/search",
        status = status,
        markers = listOf("cf-challenge"),
        referer = "https://example.com/",
    )

    @Test
    fun hiddenSolutionSkipsVisible() = runTest {
        val hidden = FakeSolver(CfSolution(mapOf("cf_clearance" to "hidden"), null))
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        val solution = subject.solve(challenge())

        assertEquals("hidden", solution?.cookies?.get("cf_clearance"))
        assertEquals(1, hidden.calls)
        assertEquals(0, visible.calls)
    }

    @Test
    fun hiddenNullDoesNotOpenVisible() = runTest {
        val hidden = FakeSolver(null)
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        assertNull(subject.solve(challenge()))
        assertEquals(1, hidden.calls)
        assertEquals(0, visible.calls)
    }

    @Test
    fun solveVisibleOpensVisible() = runTest {
        val hidden = FakeSolver(CfSolution(mapOf("cf_clearance" to "hidden"), null))
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        val solution = subject.solveVisible(challenge())

        assertEquals("visible", solution?.cookies?.get("cf_clearance"))
        assertEquals(0, hidden.calls)
        assertEquals(1, visible.calls)
    }

    @Test
    fun hiddenFailureReturnsNull() = runTest {
        val hidden = FakeSolver(fail = true)
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        assertNull(subject.solve(challenge()))
        assertEquals(0, visible.calls)
    }

    @Test
    fun bare429SkipsHidden() = runTest {
        val hidden = FakeSolver(CfSolution(mapOf("cf_clearance" to "hidden"), null))
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        // No markers, no cf-mitigated: the origin's own rate limit.
        assertNull(subject.solve(challenge(status = 429).copy(markers = emptyList())))
        assertEquals(0, hidden.calls)
        assertEquals(0, visible.calls)
    }

    @Test
    fun cfMarked429RunsHidden() = runTest {
        val hidden = FakeSolver(CfSolution(mapOf("cf_clearance" to "hidden"), null))
        val visible = FakeSolver(CfSolution(mapOf("cf_clearance" to "visible"), null))
        val subject = TwoPhaseCfSolver(hidden, visible)

        // Explicit CF evidence: a marker (or cf-mitigated) keeps the hidden
        // auto-solve available on a 429.
        val solution = subject.solve(challenge(status = 429))

        assertEquals("hidden", solution?.cookies?.get("cf_clearance"))
        assertEquals(1, hidden.calls)
        assertEquals(0, visible.calls)

        val cfMitigated = subject.solve(challenge(status = 429).copy(markers = emptyList(), cfMitigated = true))
        assertEquals("hidden", cfMitigated?.cookies?.get("cf_clearance"))
        assertEquals(2, hidden.calls)
    }
}
