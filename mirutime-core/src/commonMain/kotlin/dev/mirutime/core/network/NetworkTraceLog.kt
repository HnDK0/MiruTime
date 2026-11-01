package dev.mirutime.core.network

import io.ktor.http.HttpHeaders
import io.ktor.http.Url

// Pure, redacted formatter for the debug network sink. One line per attempt:
// the whole point is to explain a redirect/CF failure without ever writing a
// query, body, cookie value, Authorization value, UA value, title or chapter
// text to logcat. Unit-tested by DebugLogRedactionTest.
//
// Header policy:
//  - names are always listed (they identify the request shape);
//  - values only for headers that cannot carry user content/secrets:
//    Accept, Content-Type, Content-Length, Accept-Language, X-Requested-With;
//  - Cookie -> names + cf_clearance presence only;
//  - Authorization/Proxy-Authorization -> "redacted";
//  - User-Agent -> present, Referer -> host+path (never its query).
// Response headers are names only.
fun formatNetworkTrace(event: NetworkTraceEvent): String = buildString {
    append("event=http method=").append(event.method)
    append(" url=").append(safeHostPath(event.url))
    append(" status=").append(event.status)
    append(" bytes=").append(event.bodyBytes)
    append(" elapsed=").append(event.durationMs).append("ms")
    append(" hop=").append(event.redirectHop)
    append(" cfBlocked=").append(event.cfBlocked)
    event.cfTrigger?.let { append(" cfTrigger=").append(it) }
    append(" plugin=").append(event.plugin ?: "-")
    append(" args=").append(event.args ?: "-")
    append(" cookies=").append(event.cookiesStored)
    append(" applies=").append(event.cookiesApplied).append("/").append(event.cookiesStored)
    append(" cf_clearance=").append(if (event.cfClearance) "present" else "absent")

    append(" headers=").append(event.requestHeaders.keys.joinToString(","))
    appendSafeRequestHeader(event.requestHeaders, HttpHeaders.Accept, "accept")
    appendSafeRequestHeader(event.requestHeaders, HttpHeaders.ContentType, "content-type")
    appendSafeRequestHeader(event.requestHeaders, HttpHeaders.ContentLength, "content-length")
    appendSafeRequestHeader(event.requestHeaders, HttpHeaders.AcceptLanguage, "accept-language")
    appendSafeRequestHeader(event.requestHeaders, "X-Requested-With", "x-requested-with")

    val cookieNames = headerValue(event.requestHeaders, HttpHeaders.Cookie)
        ?.split(';')
        ?.map { it.substringBefore('=').trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (cookieNames.isNotEmpty()) {
        append(" cookie-names=").append(cookieNames.joinToString(","))
    }
    appendPresence(event.requestHeaders, "Authorization", "authorization=redacted")
    appendPresence(event.requestHeaders, "Proxy-Authorization", "proxy-authorization=redacted")
    appendPresence(event.requestHeaders, HttpHeaders.UserAgent, "ua=present")
    headerValue(event.requestHeaders, "Referer")?.let {
        append(" referer=").append(safeHostPath(it))
    }

    append(" response-headers=")
        .append(event.responseHeaders.keys.joinToString(","))
}

// Scheme + host + path only: the query string and fragment never reach the
// log. Scheme and a non-default port are kept so an http->https or :8080 hop
// is distinguishable. Relative Location values (the FWN 301 case) are not
// URLs on their own; Ktor's Url would invent a host for "localhost/search-adv".
fun safeHostPath(url: String): String {
    if (!url.contains("://")) {
        return url.substringBefore('?').substringBefore('#').substringAfter("@").take(120)
    }
    return try {
        val parsed = Url(url)
        val scheme = parsed.protocol.name
        val defaultPort = when (scheme) {
            "http", "ws" -> 80
            "https", "wss" -> 443
            else -> -1
        }
        val port = if (parsed.port != defaultPort) ":${parsed.port}" else ""
        "$scheme://${parsed.host}$port${parsed.encodedPath}"
    } catch (_: Exception) {
        // Unparsable URL: strip the userinfo so credentials cannot leak, and
        // keep the same query/fragment/length policy as the parsed path.
        url.substringBefore('?').substringBefore('#')
            .substringAfter("://")
            .substringAfter("@")
            .take(120)
    }
}

private fun StringBuilder.appendSafeRequestHeader(
    headers: Map<String, String>,
    name: String,
    label: String,
) {
    val value = headerValue(headers, name) ?: return
    append(' ').append(label).append('=').append(value.replace('\n', ' ').take(120))
}

private fun StringBuilder.appendPresence(headers: Map<String, String>, name: String, text: String) {
    if (headerValue(headers, name) != null) append(' ').append(text)
}

private fun headerValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
