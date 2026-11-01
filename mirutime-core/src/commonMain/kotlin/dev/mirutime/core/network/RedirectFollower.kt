package dev.mirutime.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import dev.mirutime.core.network.security.SsrfBlockedException

// Single-hop sender supplied by PluginHttpClient. Everything that varies per
// CALL (timeoutMs, contentType, budget flag) stays in its closure, so this seam
// only exposes what varies per HOP: method, URL, per-hop headers, body, UA
// override. The pair carries the response ALREADY body-limited: the size limiter
// runs between "send" and "decide", exactly where the loop used to run it itself.
internal typealias SendHop = suspend (
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    userAgentOverride: String?,
) -> Pair<HttpResponse, ByteArray>

// Walks 3xx hops by hand (default on). Ktor's built-in follower has no per-hop
// hook, so the SSRF guard must re-validate EVERY target before the next request
// goes out — that is the whole reason this loop exists.
// Policy lives here: hop counting, Location resolution, browser-shaped method
// downgrade and per-hop header hygiene. Transport lives behind sendHop.
internal suspend fun fetchFollowingRedirects(
    sendHop: SendHop,
    // Transport policy + the optional address gate, applied to the raw target
    // URL. Throws SsrfBlockedException, which the blocked=ssrf trace expects.
    checkSsrf: (url: String) -> Unit,
    deadline: CallDeadline,
    method: String,
    url: String,
    headers: Map<String, String>,
    followRedirects: Boolean,
    body: String?,
    userAgentOverride: String? = null,
    // JS/meta navigation following (JsRedirectResolver): an HTML GET response
    // carrying a meta-refresh or location= navigation is followed like a 3xx,
    // SSRF-validated and bounded by the same hop cap. Follows the
    // followRedirects flag by default — a caller resolving hosters from a raw
    // 3xx (followRedirects=false) gets no JS hop either.
    followJsRedirects: Boolean = followRedirects,
    // Debug-only sink for one redacted line per hop (raw + resolved target,
    // blocked reason). null costs nothing.
    onRedirect: ((String) -> Unit)? = null,
    // Per-client hop cap (PluginHttpClient.maxRedirects); 20 is the default.
    maxRedirects: Int = MAX_REDIRECTS,
): Pair<HttpResponse, ByteArray> {
    var currentUrl = url
    var currentMethod = method
    var currentHeaders = headers
    var currentBody = body
    var redirects = 0
    // Log-only loop detection: following semantics stay untouched.
    val seen = mutableSetOf(currentUrl)
    while (true) {
        // Boundary (a): never start a hop the deadline cannot pay for.
        deadline.throwIfExpired(currentUrl)
        val (response, bytes) = sendHop(currentMethod, currentUrl, currentHeaders, currentBody, userAgentOverride)
        if (!followRedirects) return response to bytes
        if (response.status.value in REDIRECT_CODES) {
            val location = response.headers[HttpHeaders.Location]
            // Empty Location: nothing to follow (same as a missing header).
            if (location.isNullOrBlank()) {
                onRedirect?.invoke(
                    formatRedirectTrace(
                        response.status.value, currentUrl, location, null,
                        blocked = "none", loop = false, hop = redirects + 1,
                    ),
                )
                return response to bytes
            }
            val nextUrl = try {
                resolveRedirectLocation(currentUrl, location)
            } catch (e: Exception) {
                onRedirect?.invoke(
                    formatRedirectTrace(
                        response.status.value,
                        currentUrl,
                        location,
                        nextUrl = null,
                        blocked = "invalid",
                        loop = false,
                        hop = redirects + 1,
                    ),
                )
                return response to bytes
            }
            if (nextUrl == null) {
                // Fragment-only Location: the document is already loaded, so a
                // browser does not re-request it and no hop is counted.
                onRedirect?.invoke(
                    formatRedirectTrace(
                        response.status.value,
                        currentUrl,
                        location,
                        currentUrl,
                        blocked = "none",
                        loop = false,
                        hop = redirects + 1,
                    ),
                )
                return response to bytes
            }
            val loop = nextUrl in seen
            // Security first: a hop into a private/metadata target is an SSRF
            // block even at the hop cap — the target is the reason, not the
            // redirect count, and blocked=ssrf must win over blocked=max.
            try {
                checkSsrf(nextUrl)
            } catch (e: SsrfBlockedException) {
                onRedirect?.invoke(
                    formatRedirectTrace(
                        response.status.value,
                        currentUrl,
                        location,
                        nextUrl,
                        blocked = "ssrf",
                        loop = loop,
                        hop = redirects + 1,
                    ),
                )
                throw e
            }
            if (redirects >= maxRedirects) {
                onRedirect?.invoke(
                    formatRedirectTrace(
                        response.status.value,
                        currentUrl,
                        location,
                        nextUrl,
                        blocked = "max",
                        loop = loop,
                        hop = redirects + 1,
                    ),
                )
                // Silent success here made a redirect loop look like an empty
                // 3xx body; overflow is a transport failure, not a document.
                throw RedirectLimitException(currentUrl)
            }
            // Diagnostics: raw Location AND the resolved host+path, so a
            // relative `localhost/search-adv` is explainable without guessing.
            onRedirect?.invoke(
                formatRedirectTrace(
                    response.status.value,
                    currentUrl,
                    location,
                    nextUrl,
                    blocked = "none",
                    loop = loop,
                    hop = redirects + 1,
                ),
            )
            seen += nextUrl
            // 303 asks for a GET; 301/302 degrade a body-carrying method (browser
            // behaviour); 307/308 preserve method and body.
            // OkHttp — NoveLA's Android engine — PRESERVES HEAD on every status, so
            // the downgrade must never touch a method that carries no body.
            val degrades =
                response.status.value == 303 ||
                    (currentBody != null && response.status.value != 307 && response.status.value != 308)
            val dropsBody = degrades && currentMethod != "GET" && currentMethod != "HEAD"
            if (dropsBody) {
                currentMethod = "GET"
                currentBody = null
            }
            currentHeaders = redirectHeaders(currentHeaders, from = currentUrl, to = nextUrl, dropsBody = dropsBody)
            currentUrl = nextUrl
            redirects++
            continue
        }
        // JS navigation hop (NoveLA JsRedirectResolver, used by its
        // DownloaderRepository on chapter pages): a 200 HTML page that is only
        // a "Loading..." wrapper around window.location/meta-refresh. GET +
        // HTML only, Referer = the page it came from (NoveLA sets Referer:
        // realUrl on the manual hop), same SSRF + MAX_REDIRECTS bounds.
        // ceiling: pattern-matched — see JsRedirectResolver.
        val jsTarget = if (followJsRedirects) jsRedirectTarget(response, bytes, currentUrl, currentMethod) else null
        if (jsTarget == null) return response to bytes
        // Same order as a 3xx hop: the target's address is the block reason.
        try {
            checkSsrf(jsTarget)
        } catch (e: SsrfBlockedException) {
            onRedirect?.invoke(
                formatRedirectTrace(
                    response.status.value,
                    currentUrl,
                    rawLocation = null,
                    nextUrl = jsTarget,
                    blocked = "ssrf",
                    loop = false,
                    hop = redirects + 1,
                ),
            )
            throw e
        }
        if (redirects >= maxRedirects) {
            onRedirect?.invoke(
                formatRedirectTrace(
                    response.status.value,
                    currentUrl,
                    rawLocation = null,
                    nextUrl = jsTarget,
                    blocked = "max",
                    loop = false,
                    hop = redirects + 1,
                ),
            )
            throw RedirectLimitException(currentUrl)
        }
        // Same header hygiene as a 3xx hop (cross-origin drops Authorization)
        // plus the Referer NoveLA sets on its manual JS hop.
        currentHeaders = redirectHeaders(currentHeaders, from = currentUrl, to = jsTarget, dropsBody = false) +
            mapOf("Referer" to currentUrl)
        currentUrl = jsTarget
        redirects++
    }
}

// Non-null exactly when this hop should continue via a JS navigation:
// GET, HTML body, resolver found a non-self http(s) target.
private fun jsRedirectTarget(
    response: HttpResponse,
    bytes: ByteArray,
    currentUrl: String,
    currentMethod: String,
): String? {
    if (currentMethod != "GET") return null
    if (response.headers[HttpHeaders.ContentType]?.contains("text/html", ignoreCase = true) != true) return null
    val target = JsRedirectResolver.resolveRedirectUrl(bytes.decodeToString(), currentUrl)
        ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) }
        ?: return null
    if (target == currentUrl) return null
    return target
}

// RFC 3986 §5.2 reference resolution for a Location header.
// Ktor's `URLBuilder().takeFrom(location)` inherits the BASE query when the
// reference carries none; RFC 3986 §5.3 gives a reference without a query part
// NO query. FWN answers `301 Location: /search-adv` to drop `?genre_match=…`,
// so inheriting the query re-requested the identical URL until MAX_REDIRECTS.
//
// Null means "no hop": a fragment-only reference is the already-loaded
// document, and re-requesting it is exactly the self-loop this avoids.
// Throws when the reference cannot produce an http(s) URL.
internal fun resolveRedirectLocation(baseUrl: String, location: String): String? {
    // A fragment never reaches the server (RFC 3986 §3.5): drop it and decide.
    val ref = location.trim().substringBefore('#')
    if (ref.isEmpty()) return null

    SCHEME_PREFIX.find(ref)?.let { match ->
        val scheme = match.groupValues[1]
        require(scheme.equals("http", true) || scheme.equals("https", true)) {
            "unsupported redirect scheme: $scheme"
        }
        // Absolute reference: its own query, none if absent.
        return ref
    }

    val base = baseUrl.trim().substringBefore('#')
    val schemeEnd = base.indexOf("://")
    require(schemeEnd > 0) { "base url is not absolute" }
    val baseScheme = base.substring(0, schemeEnd)
    val baseNoQuery = base.substringBefore('?')
    val authorityStart = schemeEnd + 3
    val authorityEnd = baseNoQuery.indexOf('/', authorityStart)
    val authority =
        if (authorityEnd < 0) baseNoQuery.substring(authorityStart)
        else baseNoQuery.substring(authorityStart, authorityEnd)
    val basePath = if (authorityEnd < 0) "" else baseNoQuery.substring(authorityEnd)

    val refNoQuery = ref.substringBefore('?')
    val refQuery = if ('?' in ref) "?" + ref.substringAfter('?') else ""

    return when {
        // `//host/path`: keep the base scheme, take the reference's authority.
        ref.startsWith("//") -> {
            val rest = refNoQuery.substring(2)
            val hostEnd = rest.indexOf('/')
            val refHost = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
            require(refHost.isNotEmpty()) { "empty redirect authority" }
            val path = if (hostEnd < 0) "" else rest.substring(hostEnd)
            "$baseScheme://$refHost${removeDotSegments(path)}$refQuery"
        }
        // `?query` only: same scheme/host/port/path, the reference's query.
        ref.startsWith("?") -> baseNoQuery + ref
        // `/path`: replace the path, query cleared unless the reference has one.
        ref.startsWith("/") -> "$baseScheme://$authority${removeDotSegments(refNoQuery)}$refQuery"
        // `path` / `../path`: merge with the base directory.
        else -> {
            val dir = if (basePath.isEmpty()) "/" else basePath.substring(0, basePath.lastIndexOf('/') + 1)
            "$baseScheme://$authority${removeDotSegments(dir + refNoQuery)}$refQuery"
        }
    }
}

private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

// RFC 3986 §5.2.4. A trailing slash is kept so `/a/b/..` stays a directory.
private fun removeDotSegments(path: String): String {
    val absolute = path.startsWith("/")
    val trailingSlash = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
    val out = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> when {
                out.isNotEmpty() && out.last() != ".." -> out.removeAt(out.lastIndex)
                !absolute -> out += ".."
            }
            else -> out += segment
        }
    }
    val joined = out.joinToString("/")
    return when {
        absolute -> "/$joined" + if (trailingSlash && joined.isNotEmpty()) "/" else ""
        else -> joined + if (trailingSlash && joined.isNotEmpty()) "/" else ""
    }
}

// OkHttp followUpRequest parity. A cross-origin hop (scheme/host/port) must not
// carry the caller's Authorization or Cookie any further — sendRequest re-applies
// the cookie store for the new URL, so only that origin's cookies go out — and a
// hop whose body was dropped must not keep its entity headers.
// ceiling: only CALLER-supplied headers are filtered here — the UA and the
// Chrome header set are re-applied per hop by sendRequest, and per-host UA
// overrides are intentionally origin-independent.
internal fun redirectHeaders(
    headers: Map<String, String>,
    from: String,
    to: String,
    dropsBody: Boolean,
): Map<String, String> {
    if (headers.isEmpty()) return headers
    val crossOrigin = !sameOrigin(from, to)
    return headers.filterKeys { name ->
        when {
            crossOrigin && name.equals(HttpHeaders.Authorization, ignoreCase = true) -> false
            crossOrigin && name.equals(HttpHeaders.Cookie, ignoreCase = true) -> false
            dropsBody && name in BODY_HEADERS -> false
            else -> true
        }
    }
}

private val BODY_HEADERS = setOf(
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
)

// One redacted redirect line: raw Location AND the resolved host+path explain a
// relative hop (FWN `Location: localhost/search-adv` resolves against the base
// path) without logging the query string or full URL.
internal fun formatRedirectTrace(
    status: Int,
    from: String,
    rawLocation: String?,
    nextUrl: String?,
    blocked: String,
    loop: Boolean,
    protection: String = "none",
    hop: Int = 1,
): String = buildString {
    append("event=redirect hop=").append(hop)
    append(" status=").append(status)
    append(" from=").append(safeHostPath(from))
    append(" raw=").append(rawLocation?.let(::safeHostPath) ?: "none")
    append(" rawQuery=").append(if (rawLocation?.contains('?') == true) "present" else "absent")
    append(" next=").append(nextUrl?.let(::safeHostPath) ?: "none")
    append(" nextQuery=").append(if (nextUrl?.contains('?') == true) "present" else "absent")
    // Full-URL equality including the query: computed, never printed.
    append(" same=").append(nextUrl != null && nextUrl == from)
    append(" protection=").append(protection)
    append(" blocked=").append(blocked)
    if (loop) append(" loop=true")
}

private fun sameOrigin(from: String, to: String): Boolean {
    val a = runCatching { Url(from) }.getOrNull() ?: return true
    val b = runCatching { Url(to) }.getOrNull() ?: return true
    return a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
}

// Redirect status codes followed by fetchFollowingRedirects.
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

// Typed redirect overflow: the page that answered the last 3xx (or carried the
// last JS hop) travels in an internal property — the published :mirutime-core
// ABI must not expose a URL field. The message stays URL-free too:
// NetworkBindings surfaces this as PluginError.Network.
class RedirectLimitException(internal val url: String) :
    IllegalStateException("too many redirects")

// Default per-call hop cap (PluginHttpClient.maxRedirects can lower it).
internal const val MAX_REDIRECTS = 20
