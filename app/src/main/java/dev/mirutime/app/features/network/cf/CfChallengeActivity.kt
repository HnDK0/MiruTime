package dev.mirutime.app.features.network.cf

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.di.host.parseCookieHeader

// Visible, non-exported CF challenge screen. Loads only the SSRF-validated URL
// handed over by AndroidCfChallengeSolver; the solver owns the timeout and the
// clearance probe, this Activity only renders the site, keeps the bound UA and
// publishes cookie snapshots.
class CfChallengeActivity : ComponentActivity() {

    // Same per-app language as the rest of the app: these screens are separate
    // activities, so the activity base context has to be wrapped here too.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase, AppSettings.appLanguage))
    }

    private var webView: WebView? = null
    private var pending: PendingChallenge? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastClearance: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val challenge = CfChallengeBridge.take()
        if (challenge == null) {
            finish()
            return
        }
        pending = challenge
        challenge.onClose = { finish() }
        onBackPressedDispatcher.addCallback(this) { finishChallenge() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val title = TextView(this).apply {
            text = getString(R.string.cf_challenge_title)
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val host = TextView(this).apply {
            text = getString(R.string.cf_challenge_host_fmt, challenge.host)
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val statusView = TextView(this).apply {
            text = getString(R.string.cf_challenge_status)
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        val cancel = Button(this).apply {
            text = getString(R.string.cf_challenge_cancel)
            setOnClickListener { finishChallenge() }
        }
        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.applyChallengeDefaults(this)
        }
        webView = web
        // cf_clearance is bound to the UA fingerprint: present the exact UA the
        // challenged request used so the post-solve retry stays clear.
        challenge.requestedUserAgent?.takeIf { it.isNotBlank() }?.let { ua ->
            web.settings.userAgentString = ua
        }
        // The WebView reads the shared WebView-backed store directly, so a
        // session/login cookie obtained by http_get is already presented.
        MiruCoreLogger.d(
            TAG,
            "CF screen host=${challenge.host} " +
                "ua=${if (challenge.requestedUserAgent.isNullOrBlank()) "default" else "bound"}",
        )
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Only web schemes; a challenge page must not open file/content.
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                statusView.text = getString(R.string.cf_challenge_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val manager = CookieManager.getInstance()
                manager.flush()
                challenge.cookies = parseCookieHeader(manager.getCookie(challenge.cookieUrl))
                challenge.userAgent = view?.settings?.userAgentString
                val clearance = challenge.cookies[CfChallengeCoordinator.CF_CLEARANCE]
                if (clearance != null) lastClearance = clearance
                statusView.text = if (clearance != null) {
                    getString(R.string.cf_challenge_checking)
                } else {
                    getString(R.string.cf_challenge_waiting)
                }
                challenge.events.trySend(CfPageEvent.Loaded)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == false) return
                statusView.text = getString(R.string.cf_challenge_error)
            }
        }

        // Turnstile/CF can set cf_clearance AFTER onPageFinished with no new
        // page load. Poll the cookie jar while the screen is alive and wake the
        // coordinator as soon as a new cf_clearance appears.
        val pollCookies = object : Runnable {
            override fun run() {
                val manager = CookieManager.getInstance()
                manager.flush()
                val snapshot = parseCookieHeader(manager.getCookie(challenge.cookieUrl))
                challenge.cookies = snapshot
                webView?.settings?.userAgentString?.let { challenge.userAgent = it }
                if (hasNewClearance(lastClearance, snapshot)) {
                    lastClearance = snapshot[CfChallengeCoordinator.CF_CLEARANCE]
                    MiruCoreLogger.d(TAG, "CF clearance cookie observed host=${challenge.host}")
                    statusView.text = getString(R.string.cf_challenge_checking)
                    challenge.events.trySend(CfPageEvent.Loaded)
                }
                handler.postDelayed(this, COOKIE_POLL_MS)
            }
        }
        handler.postDelayed(pollCookies, COOKIE_POLL_MS)

        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(cancel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)

        web.loadUrl(challenge.navigateUrl, mapOf("Cache-Control" to "no-store"))
    }

    private fun finishChallenge() {
        pending?.finish()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pending?.finish()
        webView?.let { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            web.clearHistory()
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    private fun WebSettings.applyChallengeDefaults(webView: WebView) {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        allowFileAccess = false
        allowContentAccess = false
        // A cached interstitial can carry a stale token: always fetch live.
        cacheMode = WebSettings.LOAD_NO_CACHE
        // UA is bound per challenge in onCreate; keep normal JS/storage defaults.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CfChallenge"
        const val COOKIE_POLL_MS = 800L
    }
}
