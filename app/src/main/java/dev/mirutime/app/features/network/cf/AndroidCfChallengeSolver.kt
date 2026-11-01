package dev.mirutime.app.features.network.cf

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.core.host.CfBudget
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.Logger
import dev.mirutime.core.network.safeHostPath
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

// Bridges the suspending solver and the visible Activity. Exactly one challenge
// is live at a time (enforced by CfChallengeCoordinator), so a single slot is
// enough; a second offer means a wiring bug, not a queue.
internal object CfChallengeBridge {
    private var pending: PendingChallenge? = null

    @Synchronized
    fun offer(challenge: PendingChallenge): Boolean {
        if (pending != null) return false
        pending = challenge
        return true
    }

    @Synchronized
    fun take(): PendingChallenge? = pending.also { pending = null }

    @Synchronized
    fun clear(challenge: PendingChallenge) {
        if (pending === challenge) pending = null
    }
}

internal sealed interface CfPageEvent {
    data object Loaded : CfPageEvent

    data object Closed : CfPageEvent
}

internal class PendingChallenge(
    val host: String,
    val navigateUrl: String,
    val cookieUrl: String,
    // UA resolved by the host for the challenged request. The WebView must
    // present it, otherwise the cf_clearance it yields is bound to a different
    // fingerprint and the retry is re-challenged.
    val requestedUserAgent: String? = null,
) {
    val events = Channel<CfPageEvent>(Channel.CONFLATED)
    private val cleared = AtomicBoolean(false)

    // Actual UA read from the live WebView; requestedUserAgent is the value it
    // was configured with.
    @Volatile
    var userAgent: String? = requestedUserAgent

    // Snapshot written by the Activity on each page finish (main thread); the
    // platform cookie store is never read from the solver's IO thread.
    @Volatile
    var cookies: Map<String, String> = emptyMap()

    // Set by the Activity; closes the screen from the solver side (timeout).
    @Volatile
    var onClose: (() -> Unit)? = null

    fun finish() {
        if (!cleared.compareAndSet(false, true)) return
        events.trySend(CfPageEvent.Closed)
        events.close()
        CfChallengeBridge.clear(this)
    }
}

private class AndroidCfChallengeSession(
    private val pending: PendingChallenge,
) : CfChallengeSession {
    override val userAgent: String?
        get() = pending.userAgent

    override suspend fun awaitPageLoad(): Boolean =
        when (pending.events.receiveCatching().getOrNull()) {
            CfPageEvent.Loaded -> true
            CfPageEvent.Closed, null -> false
        }

    override fun cookies(): Map<String, String> = pending.cookies

    override fun close() {
        pending.onClose?.let { close ->
            Handler(Looper.getMainLooper()).post(close)
        }
        pending.finish()
    }
}

class ActivityCfChallengeUi(
    private val context: Context,
) : CfChallengeUi {

    override suspend fun open(request: CfChallenge, navigateUrl: String): CfChallengeSession? {
        // Android 10+ blocks background activity starts. Fail closed with a
        // clear log instead of opening a screen the user will never see.
        if (!isProcessForeground()) {
            MiruCoreLogger.w(TAG, "CF screen blocked host=${request.host} reason=background")
            return null
        }
        // The WebView reads the shared WebView-backed store directly, so a
        // login/session cookie from http_get is already presented.
        val pending = PendingChallenge(
            host = request.host,
            navigateUrl = navigateUrl,
            cookieUrl = request.url,
            requestedUserAgent = request.userAgent,
        )
        if (!CfChallengeBridge.offer(pending)) {
            MiruCoreLogger.w(TAG, "CF screen busy host=${request.host}")
            return null
        }
        return try {
            val intent = Intent(context, CfChallengeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AndroidCfChallengeSession(pending)
        } catch (e: Exception) {
            MiruCoreLogger.w(TAG, "CF screen start failed host=${request.host} reason=${e::class.simpleName}")
            pending.finish()
            null
        }
    }

    private fun isProcessForeground(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val TAG = "CfChallenge"
    }
}

class AndroidCfChallengeSolver(
    context: Context,
    probe: CfTargetProbe,
    private val logger: Logger = MiruCoreLogger,
    // AppSettings.cloudflareBypass stays authoritative (defaults on).
    private val enabled: () -> Boolean = { true },
) : CfChallengeSolver {
    // Hidden phase: off-screen WebView, no Activity. PluginHttpClient only ever
    // calls this one, and it must finish inside its 30s bridge budget.
    private val hidden = CfChallengeCoordinator(
        ui = HiddenCfChallengeUi(context.applicationContext),
        probe = probe,
        logger = logger,
        timeoutMs = CfBudget.HIDDEN_MS,
        phase = "hidden",
    )
    // Visible phase: the existing challenge screen, user-initiated only.
    private val visible = CfChallengeCoordinator(
        ui = ActivityCfChallengeUi(context.applicationContext),
        probe = probe,
        logger = logger,
        timeoutMs = CfBudget.VISIBLE_MS,
        phase = "visible",
    )
    private val phases = TwoPhaseCfSolver(hidden = hidden, visible = visible)

    override suspend fun solve(request: CfChallenge): CfSolution? {
        if (!enabled()) {
            logger.w(TAG, "CF solve skipped phase=hidden host=${request.host} reason=disabled")
            return null
        }
        return phases.solve(request)
    }

    override suspend fun solveVisible(request: CfChallenge): CfSolution? {
        if (!enabled()) {
            logger.w(TAG, "CF solve skipped phase=visible host=${request.host} reason=disabled")
            return null
        }
        logger.d(
            TAG,
            "event=cf verdict=challenge hidden=skipped visible=yes " +
                "trigger=${cfTrigger(request)} status=${request.status} url=${safeHostPath(request.url)}",
        )
        return phases.solveVisible(request)
    }

    private fun cfTrigger(request: CfChallenge): String = when {
        request.cfMitigated -> "cf-mitigated"
        request.markers.isNotEmpty() -> "marker"
        else -> "status"
    }

    private companion object {
        const val TAG = "CfChallenge"
    }
}

// Cookie polling only needs to wake the coordinator when a NEW cf_clearance
// appears (Turnstile can set it long after onPageFinished); every other
// snapshot is still stored for the eventual probe.
internal fun hasNewClearance(previous: String?, snapshot: Map<String, String>): Boolean =
    snapshot[CfChallengeCoordinator.CF_CLEARANCE]?.let { it != previous } == true

// Same wire shape PluginHttpClient uses when it forwards solver cookies:
// one `name=value` header per cookie, attributes dropped.
internal fun cfCookieSetHeaders(cookies: Map<String, String>): List<String> =
    cookies.map { (name, value) -> "$name=$value" }
