package dev.mirutime.app.features.network.cf

import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-phase CF solver:
 * - [solve] is the hidden auto phase (no Activity, bounded by CfBudget.HIDDEN_MS).
 *   It never touches [visible]; a failure is simply "unsolved".
 * - [solveVisible] is the user-initiated fallback (Solve button / notification)
 *   and may open a real screen.
 *
 * A bare 429 is the site's own rate limit, not a browser check: the hidden
 * phase is skipped so a rate-limited source never spends 15s on a WebView.
 * A 429 with explicit CF evidence (`cf-mitigated` or a marker) may still run
 * the hidden phase.
 *
 * One [solveMutex] covers both phases: a hidden solve in flight must finish
 * before the user opens a screen (one WebView at a time, one browser owner).
 */
class TwoPhaseCfSolver(
    private val hidden: CfChallengeSolver,
    private val visible: CfChallengeSolver,
) : CfChallengeSolver {

    private val solveMutex = Mutex()

    override suspend fun solve(request: CfChallenge): CfSolution? {
        // A bare 429 is the site's own rate limit, not a browser check.
        // Explicit CF evidence (cf-mitigated, body/trigger markers) keeps the
        // hidden auto-solve available; the status alone never does.
        if (request.status == 429 && request.markers.isEmpty() && !request.cfMitigated) return null
        return solveMutex.withLock {
            try {
                hidden.solve(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A host UI failure is "challenge unsolved", never a crash in
                // the request path.
                null
            }
        }
    }

    override suspend fun solveVisible(request: CfChallenge): CfSolution? =
        solveMutex.withLock { visible.solve(request) }
}
