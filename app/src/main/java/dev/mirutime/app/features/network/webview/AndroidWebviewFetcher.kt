package dev.mirutime.app.features.network.webview

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.di.host.parseCookieHeader
import dev.mirutime.core.host.WebviewClickSelector
import dev.mirutime.core.host.WebviewFetcher
import dev.mirutime.core.host.WebviewFetchLimit
import dev.mirutime.core.network.PluginUserAgentRegistry
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.core.network.cf.CloudflareDetector
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

// Hidden WebView for plugins that need the page's own JS to pass a captcha and
// hand back the resulting HTML (dood's Turnstile gate). No Activity, no visible
// UI, no second CF screen: a challenge or timeout ends the fetch with null.
//
// Threading: the calling plugin session worker (NetworkBindings.runBlocking)
// waits on its own thread; the WebView itself is constructed, driven and
// destroyed on Main. Everything that can block — the https + resolved-address
// pre-flight, DNS for subresources, the CF gate and every CookieManager flush/
// get — runs on IO. NEVER call fetch() from Main: the pre-flight can spend the
// whole resolver budget (six DoH providers) and would ANR the UI thread.
//
// Subresources: every request — main frame, iframe, worker, XHR, WebSocket,
// img — is blocked unless it is https AND its resolved address is public. The
// per-host verdict cache keeps a page's repeated subresources from re-resolving
// the same host.
//
// Accepted risks (unchanged): a WebView has no SafeDns hook, so the page's own
// connection re-resolves the host — a DNS-rebinding host can answer the guard
// with a public address and then connect to a private one; and the OS resolver
// used here may differ from the WebView's resolver.
internal class AndroidWebviewFetcher(
    context: Context,
    private val sourceId: String,
    private val ssrfGuard: SsrfGuard,
    private val cfGate: CfGate,
) : WebviewFetcher {

    private val context = context.applicationContext

    // WebView background threads call shouldInterceptRequest for every
    // subresource; one DNS pass per host per TTL instead of one per request.
    private val verdictCache = ConcurrentHashMap<String, CachedVerdict>()

    // Per-source gate (one fetcher instance is created per plugin source):
    // one fetch in flight and at most WebviewFetchLimit.MAX_PER_WINDOW
    // launches per minute. State is guarded by rateLock; release() runs in a
    // finally so a cancelled fetch cannot wedge the source.
    private val rateLock = Any()
    private var inFlight = 0
    private val fetchStarts = ArrayDeque<Long>()

    override suspend fun fetch(
        url: String,
        click: String?,
        expect: String?,
        timeoutMs: Long,
    ): String? {
        // KDoc contract: never from Main (blocking DNS + a host lock wait).
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val startedAt = SystemClock.uptimeMillis()
        val host = hostOf(url)
        // Defence in depth: NetworkBindings already extracted the selector, but
        // nothing that is not a selector may ever reach a page.
        val selector = click?.let { WebviewClickSelector.extract(it) }
        if (click != null && selector == null) return null
        // Over the per-source limit: nil immediately, no WebView is created.
        if (!tryAcquire(startedAt)) {
            MiruCoreLogger.d(TAG, "event=webview-fetch host=$host result=rate-limited")
            return null
        }
        try {
            var cookies = 0
            val html = try {
                // One hidden page per host at a time, sharing the process gate with
                // the plugin HTTP lane: a CF cooldown/lock timeout means null
                // instead of a second solver.
                cfGate.withHostLock(normalizeHostKey(host)) {
                    val scheme = url.substringBefore(':', missingDelimiterValue = "")
                    // Blocking: keep the initial https + resolved-address check off
                    // Main, before any WebView exists.
                    val verdict = withContext(Dispatchers.IO) {
                        if (!scheme.equals("https", ignoreCase = true)) {
                            SsrfVerdict.Blocked("scheme")
                        } else {
                            ssrfGuard.isSafeResolved(url)
                        }
                    }
                    // Seed the cache: the initial navigation and its subresources
                    // reuse this verdict instead of resolving again.
                    rememberVerdict(host, verdict)
                    if (webviewRequestDecision(scheme, verdict, isMainFrame = true) != WebviewRequestDecision.Allow) {
                        return@withHostLock null
                    }
                    val cookieManager = withContext(Dispatchers.IO) { prepareCookies() }
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            val session = WebviewSession(cookieManager, url, selector, expect)
                            try {
                                session.run(timeoutMs.coerceIn(1, MAX_FETCH_MS))
                            } finally {
                                session.close()
                            }
                        }
                    } finally {
                        // Cookie store flush is disk I/O: never on Main, and never
                        // allowed to replace the session result.
                        cookies = try {
                            withContext(NonCancellable + Dispatchers.IO) {
                                val manager = CookieManager.getInstance()
                                manager.flush()
                                parseCookieHeader(manager.getCookie(url)).size
                            }
                        } catch (_: Exception) {
                            0
                        }
                    }
                }
            } catch (_: CfChallengeRequiredException) {
                // Cooldown or lock timeout: the host is being solved elsewhere.
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            MiruCoreLogger.d(
                TAG,
                "event=webview-fetch host=$host cookies=$cookies " +
                    "elapsed=${SystemClock.uptimeMillis() - startedAt}ms",
            )
            // Same 25 MB ceiling as PluginHttpClient.maxResponseBytes. Counted in
            // UTF-16 chars so the check never re-encodes the document.
            return html?.takeIf { it.length <= MAX_HTML_CHARS }
        } finally {
            release()
        }
    }

    // Serialized by rateLock: prune the window, consult the pure policy, then
    // take the slot. A rejected call does not consume budget.
    private fun tryAcquire(nowMs: Long): Boolean = synchronized(rateLock) {
        while (fetchStarts.isNotEmpty() && nowMs - fetchStarts.first() >= WebviewFetchLimit.WINDOW_MS) {
            fetchStarts.removeFirst()
        }
        if (!WebviewFetchLimit.allows(inFlight, fetchStarts.toList(), nowMs)) return@synchronized false
        inFlight++
        fetchStarts.addLast(nowMs)
        true
    }

    private fun release() {
        synchronized(rateLock) {
            if (inFlight > 0) inFlight--
        }
    }

    // Shared cookie store (WebViewCookieStore): persist pending writes so the
    // hidden page presents the same session. Disk I/O, never on Main.
    private fun prepareCookies(): CookieManager = CookieManager.getInstance().also {
        it.setAcceptCookie(true)
        it.flush()
    }

    // Runs on a WebView background thread (shouldInterceptRequest), so the DNS
    // it performs cannot block Main.
    private fun addressVerdict(target: Uri): SsrfVerdict {
        val host = normalizeHostKey(target.host)
        if (host.isEmpty()) return SsrfVerdict.Blocked("empty host")
        val now = SystemClock.uptimeMillis()
        verdictCache[host]?.takeIf { now - it.at < VERDICT_TTL_MS }?.let { return it.verdict }
        if (verdictCache.size >= MAX_CACHED_HOSTS) verdictCache.clear()
        return ssrfGuard.isSafeResolved(target.toString()).also {
            verdictCache[host] = CachedVerdict(it, now)
        }
    }

    private fun rememberVerdict(rawHost: String, verdict: SsrfVerdict) {
        val host = normalizeHostKey(rawHost)
        if (host.isNotEmpty()) verdictCache[host] = CachedVerdict(verdict, SystemClock.uptimeMillis())
    }

    private inner class WebviewSession(
        private val cookieManager: CookieManager,
        private val startUrl: String,
        // Validated CSS selector (never plugin JS); the click script is built
        // here, after JSON-escaping.
        private val clickSelector: String?,
        private val expect: String?,
    ) {
        private val web = WebView(context)
        private var pageFinished = false
        private val closed = AtomicBoolean(false)

        // Written from the WebView's interceptor/UI callbacks, read on Main.
        @Volatile
        private var blocked = false
        private var challenged = false
        private var lastHtml: String? = null

        init {
            try {
                start()
            } catch (e: Exception) {
                // Construction failure must not leak the WebView (same recipe
                // as HiddenCfChallengeUi); we are on Main, destroy synchronously.
                runCatching { web.destroy() }
                throw e
            }
        }

        private fun start() {
            // Third-party cookies come from the same shared store; the setter
            // needs this WebView, so it is the one cookie call that stays on
            // Main (it does not touch disk).
            cookieManager.setAcceptThirdPartyCookies(web, true)
            web.settings.apply {
                javaScriptEnabled = true
                // Turnstile needs JS, not DOM storage: keep the shared profile
                // free of localStorage/sessionStorage/WebSQL.
                domStorageEnabled = false
                databaseEnabled = false
                // A hidden page never opens windows and never gets a
                // file://-to-anything origin handle.
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                // Same UA as the plugin's HTTP lane: a clearance obtained here
                // is fingerprint-bound and must match the next http_get.
                userAgentString = PluginUserAgentRegistry.resolve(sourceId, hostOf(startUrl))
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Main thread: scheme only. DNS belongs to
                    // shouldInterceptRequest, which runs on a background thread.
                    val block = webviewRequestDecision(
                        request?.url?.scheme,
                        resolvedVerdict = null,
                        isMainFrame = true,
                    ) == WebviewRequestDecision.Block
                    // A refused main-frame hop ends the fetch instead of
                    // waiting out the deadline.
                    if (block) blocked = true
                    return block
                }

                // Runs before every request on a WebView background thread,
                // including server redirects and subresources. No main-frame
                // filter: an iframe/worker/XHR/img can reach LAN hosts too.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val target = request?.url ?: return null
                    val scheme = target.scheme
                    val verdict = if (scheme.equals("https", ignoreCase = true)) {
                        addressVerdict(target)
                    } else {
                        null
                    }
                    if (webviewRequestDecision(scheme, verdict, request.isForMainFrame) ==
                        WebviewRequestDecision.Allow
                    ) {
                        return null
                    }
                    if (request.isForMainFrame) blocked = true
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageFinished = true
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true || errorResponse == null) return
                    val headers = errorResponse.responseHeaders.orEmpty()
                    val cfMitigated = headers.entries
                        .firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }?.value
                    val server = headers.entries
                        .firstOrNull { it.key.equals("server", ignoreCase = true) }?.value
                    if (CloudflareDetector.isChallengeByHeaders(errorResponse.statusCode, server, cfMitigated)) {
                        challenged = true
                    }
                }
            }
            web.loadUrl(startUrl, mapOf("Cache-Control" to "no-store"))
        }

        suspend fun run(timeoutMs: Long): String? {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            awaitDocument(deadline)
            if (blocked || challenged) return null

            clickSelector?.let { selector ->
                // The click navigates: the document on screen is pre-click, so
                // wait for the NEXT document before reading the DOM.
                pageFinished = false
                evaluate(clickScript(selector))
                awaitDocument(deadline)
                if (blocked || challenged) return null
            }

            while (SystemClock.uptimeMillis() < deadline) {
                if (blocked || challenged) return null
                val html = evaluate(HTML_SCRIPT)
                if (html != null) {
                    // A CF interstitial is not a document: no second screen.
                    if (isCfChallenge(html)) {
                        challenged = true
                        return null
                    }
                    lastHtml = html
                    // `expect` is the post-click marker: only a document that
                    // contains it can satisfy the call.
                    if (expect.isNullOrBlank() || html.contains(expect)) return html
                }
                delay(POLL_MS)
            }
            // Non-blank expect never matched: null, never the pre-click/last
            // document. A blank expect accepts the last non-CF document.
            return if (expect.isNullOrBlank()) lastHtml else null
        }

        private suspend fun awaitDocument(deadline: Long) {
            while (!pageFinished && !blocked && !challenged && SystemClock.uptimeMillis() < deadline) {
                delay(POLL_MS)
            }
        }

        // Idempotent: a cancelled fetch and its timeout path must not destroy
        // the same WebView twice. Every step is isolated so a throwing call
        // (already-detached WebView) cannot skip destroy().
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { web.stopLoading() }
            runCatching { web.loadUrl("about:blank") }
            runCatching { web.clearHistory() }
            runCatching { (web.parent as? ViewGroup)?.removeView(web) }
            runCatching { web.destroy() }
        }

        private suspend fun evaluate(script: String): String? =
            withTimeoutOrNull(EVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    web.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(decodeJsString(value))
                    }
                }
            }
    }

    private data class CachedVerdict(val verdict: SsrfVerdict, val at: Long)

    private companion object {
        const val TAG = "webview"
        const val POLL_MS = 500L
        const val MAX_FETCH_MS = 20_000L
        const val EVAL_TIMEOUT_MS = 2_000L
        // PluginHttpClient.maxResponseBytes parity: over the cap the document
        // is not handed to the plugin at all.
        const val MAX_HTML_CHARS = 25 * 1024 * 1024
        const val VERDICT_TTL_MS = 5 * 60 * 1000L
        const val MAX_CACHED_HOSTS = 128
        const val HTML_SCRIPT = "document.documentElement.outerHTML"

        // Plugin text never reaches JS: only the validated selector is
        // interpolated, JSON-escaped into a string literal.
        fun clickScript(selector: String): String =
            "(function(){var e=document.querySelector(${JSONObject.quote(selector)});" +
                "if(e){e.click();return true;}return false;})()"

        fun hostOf(url: String): String = runCatching { java.net.URI(url).host }
            .getOrNull()
            ?: url.substringAfter("://").substringBefore('/')

        // Same key PluginHttpClient uses for Cooldown/host locks (core
        // normalizeHost: lowercase, no www, no port). Duplicated because that
        // helper is internal to :mirutime-core.
        fun normalizeHostKey(raw: String?): String =
            raw.orEmpty().trim('[', ']').lowercase().removePrefix("www.")

        // evaluateJavascript delivers a JSON-encoded value; the plugin matches
        // raw HTML patterns, so decode it before returning.
        fun decodeJsString(value: String?): String? {
            if (value == null || value == "null") return null
            return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
        }

        fun isCfChallenge(html: String): Boolean =
            CloudflareDetector().isChallenge(403, html, null, requestUrl = null)
    }
}
