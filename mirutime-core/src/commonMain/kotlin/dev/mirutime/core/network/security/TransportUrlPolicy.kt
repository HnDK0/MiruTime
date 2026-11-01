package dev.mirutime.core.network.security

import io.ktor.http.Url

// Scheme + userinfo only. Every transport applies this — plugin (with the
// optional SSRF address gate) and plain clients on the system resolver
// (translation, repo/update, images) alike. It is deliberately NOT an address
// gate: private/LAN targets and non-default ports are allowed here; the plugin
// engine's SafeDns handles addresses for the plugin lane.
// One implementation: SsrfGuard.urlVerdict and PluginHttpClient.checkSsrf both
// call it, so their reason strings and allowed schemes can never drift.
internal fun transportViolation(parsed: Url): String? {
    val scheme = parsed.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") {
        return "scheme '$scheme' is not http/https"
    }
    // Userinfo is never needed and is a classic host-confusion vector
    // ("http://trusted.com@10.0.0.1/").
    if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) {
        return "credentials in url"
    }
    return null
}

// String form for host code that only carries the raw target (CF navigation
// and probe). false = malformed URL or a policy violation; address rules are
// deliberately absent, so private/LAN literals stay allowed.
fun isTransportAllowed(url: String): Boolean =
    try {
        transportViolation(Url(url)) == null
    } catch (_: Exception) {
        false
    }
