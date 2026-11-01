package dev.mirutime.app.features.network.cf

import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.Logger
import dev.mirutime.core.network.safeHostPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// One visible WebView challenge screen; created by a CfChallengeUi.
// awaitPageLoad() suspends until the WebView finishes a page (true) or the
// user/system closes the screen (false). cookies() is read after each load.
interface CfChallengeSession {
    val userAgent: String?

    suspend fun awaitPageLoad(): Boolean

    fun cookies(): Map<String, String>

    fun close()
}

interface CfChallengeUi {
    // Returns null when the screen cannot be shown (background activity-start
    // restriction, ActivityNotFound, missing screen, ...). Must never throw for
    // those cases — the solver turns null into "challenge unsolved".
    suspend fun open(request: CfChallenge, navigateUrl: String): CfChallengeSession?
}

// Verifies a clearance against the real target, not just the cookie value.
fun interface CfTargetProbe {
    suspend fun isClear(targetUrl: String, cookies: Map<String, String>, userAgent: String?): Boolean
}

// Serializes CF bypasses (one WebView at a time) and bounds the whole solve.
// The caller's PluginHttpClient already has a call budget; this timeout only
// keeps a forgotten/blank challenge screen from pinning the host lock forever.
class CfChallengeCoordinator(
    private val ui: CfChallengeUi,
    private val probe: CfTargetProbe,
    private val logger: Logger,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    // "hidden" (off-screen auto) / "visible" (user-initiated): device evidence
    // reads this field to prove which phase ran.
    private val phase: String = "visible",
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : CfChallengeSolver {
    private val mutex = Mutex()

    override suspend fun solve(request: CfChallenge): CfSolution? {
        // Never open the raw plugin-derived target: only the scheme/userinfo
        // validated navigation URL may reach the WebView.
        val navigateUrl = request.safeNavigateUrl()
        if (navigateUrl == null) {
            logger.w(TAG, "CF solve rejected phase=$phase host=${request.host} reason=unsafe-url")
            return null
        }
        val startedAtMs = clock()
        logger.d(
            TAG,
            "event=cf phase=$phase stage=navigate navigate=${safeHostPath(navigateUrl)} host=${request.host}",
        )
        MiruLog.event(
            "cf",
            "solve",
            "phase" to phase,
            "stage" to "start",
            "host" to request.host,
            "status" to request.status,
        )
        return try {
            withTimeout(timeoutMs) {
                mutex.withLock { solveLocked(request, navigateUrl, startedAtMs) }
            }
        } catch (e: TimeoutCancellationException) {
            MiruLog.event(
                "cf",
                "solve",
                "phase" to phase,
                "stage" to "timeout",
                "host" to request.host,
                "elapsedMs" to (clock() - startedAtMs),
            )
            logger.w(
                TAG,
                "CF solve timeout phase=$phase host=${request.host} status=${request.status} elapsedMs=${clock() - startedAtMs}",
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Host UI failures must surface as "challenge unsolved", never as
            // a crash in the download path.
            MiruLog.event(
                "cf",
                "solve",
                "phase" to phase,
                "stage" to "failed",
                "host" to request.host,
                "error" to (e::class.simpleName ?: "error"),
            )
            logger.w(
                TAG,
                "CF solve failed phase=$phase host=${request.host} status=${request.status} elapsedMs=${clock() - startedAtMs}: ${e::class.simpleName}",
            )
            null
        }
    }

    private suspend fun solveLocked(
        request: CfChallenge,
        navigateUrl: String,
        startedAtMs: Long,
    ): CfSolution? {
        var session: CfChallengeSession? = null
        try {
            val active = ui.open(request, navigateUrl)
            session = active
            if (active == null) {
                MiruLog.event("cf", "solve", "phase" to phase, "stage" to "no-screen", "host" to request.host)
                logger.w(TAG, "CF screen unavailable phase=$phase host=${request.host} status=${request.status}")
                return null
            }
            while (true) {
                if (!active.awaitPageLoad()) {
                    logger.w(
                        TAG,
                        "CF solve cancelled phase=$phase host=${request.host} status=${request.status} elapsedMs=${clock() - startedAtMs}",
                    )
                    return null
                }
                val cookies = active.cookies()
                if (!cookies.containsKey(CF_CLEARANCE)) {
                    MiruLog.event(
                        "cf",
                        "retry",
                        "phase" to phase,
                        "host" to request.host,
                        "reason" to "no-cookie",
                        "cookie" to false,
                    )
                    logger.d(
                        TAG,
                        "CF waiting phase=$phase host=${request.host} reason=no-cookie ua=${uaState(active.userAgent)}",
                    )
                    continue
                }
                // Probe the page the WebView actually loaded: for an XHR/no-JS
                // challenge that is the navigateUrl (Referer), while the
                // request URL answers with the challenge by definition.
                if (!probe.isClear(navigateUrl, cookies, active.userAgent)) {
                    MiruLog.event(
                        "cf",
                        "retry",
                        "phase" to phase,
                        "host" to request.host,
                        "reason" to "challenged",
                        "cookie" to true,
                    )
                    logger.w(
                        TAG,
                        "CF probe failed phase=$phase host=${request.host} reason=challenged ua=${uaState(active.userAgent)}",
                    )
                    continue
                }
                MiruLog.event(
                    "cf",
                    "solve",
                    "phase" to "cleared",
                    "mode" to phase,
                    "host" to request.host,
                    "elapsedMs" to (clock() - startedAtMs),
                    "cookie" to true,
                )
                logger.d(
                    TAG,
                    "CF cleared phase=$phase host=${request.host} status=${request.status} elapsedMs=${clock() - startedAtMs}",
                )
                return CfSolution(cookies = cookies, userAgent = active.userAgent)
            }
        } finally {
            session?.close()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val CF_CLEARANCE = "cf_clearance"
        private const val TAG = "CfChallenge"

        // Presence only — a UA value is fingerprint data and must never be
        // written to logs.
        private fun uaState(userAgent: String?): String =
            if (userAgent.isNullOrBlank()) "missing" else "present"
    }
}
