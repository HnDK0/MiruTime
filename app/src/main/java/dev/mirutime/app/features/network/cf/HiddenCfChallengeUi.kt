package dev.mirutime.app.features.network.cf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.di.host.parseCookieHeader
import dev.mirutime.core.host.CfChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-screen WebView for the hidden auto-solve phase. No Activity, no visible
 * UI; [CfChallengeCoordinator] bounds the phase with `CfBudget.HIDDEN_MS` and
 * the caller's PluginHttpClient budget stays 30s.
 *
 * Lifecycle: created and destroyed on the Main thread, destroyed in the
 * coordinator's `finally` via [CfChallengeSession.close].
 */
class HiddenCfChallengeUi(
    private val context: Context,
) : CfChallengeUi {

    override suspend fun open(request: CfChallenge, navigateUrl: String): CfChallengeSession? {
        // The WebView reads the shared WebView-backed store directly: a
        // clearance from an earlier solve is already presented.
        return withContext(Dispatchers.Main.immediate) {
            try {
                HiddenCfSession(
                    context = context.applicationContext,
                    challenge = request,
                    navigateUrl = navigateUrl,
                )
            } catch (e: Exception) {
                MiruCoreLogger.w(
                    TAG,
                    "hidden CF webview failed host=${request.host} reason=${e::class.simpleName}",
                )
                null
            }
        }
    }

    private class HiddenCfSession(
        context: Context,
        private val challenge: CfChallenge,
        private val navigateUrl: String,
    ) : CfChallengeSession {

        private val handler = Handler(Looper.getMainLooper())
        private val events = Channel<CfPageEvent>(Channel.CONFLATED)
        private val closed = AtomicBoolean(false)
        private val web = WebView(context)
        private var lastClearance: String? = null

        @Volatile
        private var snapshot: Map<String, String> = emptyMap()

        // Actual UA read back from the live WebView (requestedUserAgent is the
        // value it was configured with). Fingerprint data: never logged.
        @Volatile
        override var userAgent: String? = challenge.userAgent

        // Turnstile can set cf_clearance long after onPageFinished with no new
        // page load: poll the jar and wake the coordinator on a NEW clearance.
        private val poll = object : Runnable {
            override fun run() {
                if (closed.get()) return
                publish()
                if (hasNewClearance(lastClearance, snapshot)) {
                    lastClearance = snapshot[CfChallengeCoordinator.CF_CLEARANCE]
                    // Persist the clearance once, for the post-solve retry; the
                    // read above is in-memory, so polling must not flush.
                    CookieManager.getInstance().flush()
                    events.trySend(CfPageEvent.Loaded)
                }
                handler.postDelayed(this, COOKIE_POLL_MS)
            }
        }

        init {
            try {
                start()
            } catch (e: Exception) {
                // Creation failure must not leak the WebView; we are on Main,
                // so destroy synchronously before open() reports "unsolved".
                web.destroy()
                throw e
            }
        }

        private fun start() {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(web, true)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // A cached challenge page can carry a stale token: always fetch
                // the live interstitial.
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
            }
            // cf_clearance is UA-bound: present the exact UA of the challenged
            // request (before anything is sent) so the post-solve retry stays clear.
            challenge.userAgent?.takeIf { it.isNotBlank() }?.let { web.settings.userAgentString = it }

            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Only web schemes; the hidden page never opens file/content.
                    val scheme = request?.url?.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    publish()
                    events.trySend(CfPageEvent.Loaded)
                }
            }
            web.loadUrl(navigateUrl, mapOf("Cache-Control" to "no-store"))
            handler.postDelayed(poll, COOKIE_POLL_MS)
            MiruCoreLogger.d(
                TAG,
                "hidden CF load host=${challenge.host} " +
                    "ua=${if (challenge.userAgent.isNullOrBlank()) "default" else "bound"}",
            )
        }

        private fun publish() {
            // getCookie reads the in-memory jar: no Main-thread flush per poll.
            snapshot = parseCookieHeader(CookieManager.getInstance().getCookie(challenge.url))
            web.settings.userAgentString?.let { userAgent = it }
        }

        override suspend fun awaitPageLoad(): Boolean =
            when (events.receiveCatching().getOrNull()) {
                CfPageEvent.Loaded -> true
                CfPageEvent.Closed, null -> false
            }

        override fun cookies(): Map<String, String> = snapshot

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            events.trySend(CfPageEvent.Closed)
            events.close()
            handler.removeCallbacksAndMessages(null)
            // destroy() is Main-only; the coordinator calls close() from IO.
            Handler(Looper.getMainLooper()).post {
                web.stopLoading()
                web.loadUrl("about:blank")
                web.clearHistory()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
                CookieManager.getInstance().flush()
            }
        }
    }

    private companion object {
        const val TAG = "CfChallenge"
        const val COOKIE_POLL_MS = 500L
    }
}
