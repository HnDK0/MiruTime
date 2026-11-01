package dev.mirutime.core.host

// Hidden WebView fetch for plugins that need the page's own JS to pass a
// captcha and then read the resulting HTML (dood's Turnstile gate). The host
// implementation lives in :app; core only sees this seam.
//
// Returns null when the fetch is blocked (SSRF), times out, hits a Cloudflare
// challenge or cannot run; never throws for those — a plugin treats null as
// "no result".
//
// `click` is a CSS selector (plain or the plugin's jQuery form, already
// extracted/validated by WebviewClickSelector), never plugin JS: the host
// builds and runs the click script itself.
interface WebviewFetcher {
    suspend fun fetch(
        url: String,
        click: String?,
        expect: String?,
        timeoutMs: Long,
    ): String?
}
