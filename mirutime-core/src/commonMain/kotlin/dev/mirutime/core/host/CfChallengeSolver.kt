package dev.mirutime.core.host

import dev.mirutime.core.network.security.isTransportAllowed

data class CfChallenge(
    val host: String,
    val url: String,
    val status: Int,
    val markers: List<String>,
    val referer: String? = null,
    // The EXACT UA the challenged request used. The WebView must present this
    // same UA: cf_clearance is fingerprint-bound, so a different browser UA
    // makes the cookie useless. Null = host has no resolved UA (WebView
    // default stays).
    val userAgent: String? = null,
    // Non-GET / XHR request (ScribbleHub admin-ajax): there is no page JS
    // context, so the solver opens the Referer page instead. A form POST is a
    // browser NAVIGATION, not XHR — see isXhrRequest.
    val isXhr: Boolean = false,
    // `cf-mitigated: challenge` was on the response: explicit CF evidence that
    // separates a browser check from a bare origin 429.
    val cfMitigated: Boolean = false,
) {
    // NoveLA parity (CloudfareVerificationInterceptor.kt:302-306): a challenge
    // on an /api/ path has no browser JS context — the solver opens the
    // REFERER page (a normal site page) instead; the cf_clearance it yields
    // covers the whole host, API endpoints included. Same for XHR/no-JS
    // requests. A form POST keeps its own URL: CF serves it a real page.
    // No referer -> the request URL stays the solver target.
    val navigateUrl: String
        get() = if (isXhr && !referer.isNullOrBlank()) referer else url

    // The solver target is plugin-derived (caller Referer header or request
    // URL): the host MUST NOT open it in a browser/WebView blindly — an evil
    // plugin could point it at a file:/javascript: URL or a userinfo
    // host-confusion target. Returns navigateUrl only when it passes
    // TransportUrlPolicy (scheme/userinfo), else null (fail closed). Address
    // rules are NOT part of this policy: the WebView lane keeps its own
    // pre-connect address guard (AndroidWebviewFetcher).
    fun safeNavigateUrl(): String? =
        navigateUrl.takeIf { isTransportAllowed(it) }
}

data class CfSolution(
    val cookies: Map<String, String>,
    val userAgent: String?,
)

// Solve-phase time budgets. The hidden phase runs inside the bridge's 30s call
// budget (NetworkBindings.DEFAULT_TIMEOUT_MS) and must never approach it; the
// visible phase is user-initiated and may outlive that budget because the user
// is watching the screen.
object CfBudget {
    const val HIDDEN_MS: Long = 15_000
    const val VISIBLE_MS: Long = 35_000
}

interface CfChallengeSolver {
    // The host MUST open request.safeNavigateUrl(), never the raw
    // navigateUrl — the target is plugin-derived (see above).
    // The host MUST validate the clearance with a live request before
    // reporting success (NoveLA probeIsClear pattern): a cf_clearance cookie
    // alone proves nothing until a request carrying it + the browser UA comes
    // back challenge-free. No WebView lives in core — this probe is host code.
    // This is the HIDDEN phase: auto-solve, no screen. PluginHttpClient calls
    // only this one.
    suspend fun solve(request: CfChallenge): CfSolution?

    // User-initiated fallback (Solve button / notification): may open a visible
    // screen and is never called by PluginHttpClient.
    suspend fun solveVisible(request: CfChallenge): CfSolution? = solve(request)
}
