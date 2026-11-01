package dev.mirutime.core.network.cf

// Per-plugin CF detection config; null on PluginMetadata means defaults below.
// Mirrors NoveLA's CfDomainOptions (whitelist / ignore_markers / trigger_markers).
data class CfOptions(
    val statuses: List<Int> = listOf(403, 429, 503),
    val markers: List<String> = DEFAULT_MARKERS,
    val whitelist: Boolean = false,
    val ignoreMarkers: List<String> = emptyList(),
    val triggerMarkers: List<String> = emptyList(),
) {
    companion object {
        // NoveLA marker set (CloudfareVerificationInterceptor.ALL_CF_MARKERS,
        // verbatim). Deliberately NOT included: the bare phrase "Just a moment"
        // — fictionzone search (search_in_synopsis=true) returns book synopses
        // that legitimately contain it, and the API edge is server: cloudflare,
        // so the phrase alone produced a permanent false-positive challenge on
        // every search. Real interstitials always carry a technical marker from
        // this list (/cdn-cgi/challenge-platform/, __cf_chl_, cf-wrapper, ...).
        val DEFAULT_MARKERS: List<String> = listOf(
            "cf-challenge",
            "requireTurnstile",
            "action=\"/cdn-cgi/challenge-platform/",
            "onloadTurnstileCallback",
            "__cf_chl_",
            "but-captcha",
            "recaptcha-accessible-status",
            "cf-browser-verification",
            "cf-challenge-running",
            "cf-please-wait",
            "id=\"challenge-running\"",
            "id=\"cf-challenge-running\"",
            "ddos-guard.net",
            ".ddos-guard.net",
            "cf-error-details",
            "cf-subheadline",
            "cf-wrapper",
            "Attention Required! | Cloudflare",
        )
    }
}

class CloudflareDetector {
    fun isChallenge(
        status: Int,
        body: String,
        cfOptions: CfOptions?,
        serverHeader: String? = null,
        cfMitigatedHeader: String? = null,
        // URL of the request that produced this response (null = unknown):
        // drives the static-asset shortcut, the built-in host whitelist and
        // the IP-block markers below. Optional and trailing, so existing
        // callers keep compiling.
        requestUrl: String? = null,
    ): Boolean = isChallenge(status, { body }, cfOptions, serverHeader, cfMitigatedHeader, requestUrl)

    // Lazy view of the same predicate: the provider is called at most once and
    // only after every metadata/URL branch left the verdict to a marker scan.
    // null = the caller deliberately did not materialize the body (large
    // success bodies on a client that cannot solve): marker/trigger checks are
    // skipped, metadata branches still decide. One implementation — no
    // condition is added or removed for a caller that supplies a body.
    fun isChallenge(
        status: Int,
        body: (() -> String)?,
        cfOptions: CfOptions?,
        serverHeader: String? = null,
        cfMitigatedHeader: String? = null,
        requestUrl: String? = null,
    ): Boolean {
        // Static assets are never challenges (NoveLA
        // CloudfareVerificationInterceptor.isNotCloudflare: `if (skipStatic &&
        // pathExt in STATIC_EXTENSIONS) return true`): cover/proxy CDNs answer
        // error statuses with the origin's body, and a bypass there is
        // pointless (a cf_clearance baked for the image host helps nothing).
        if (requestUrl != null && hasStaticExtension(requestUrl)) return false
        // Built-in host whitelist (NoveLA CLOUDFLARE_WHITELIST): known-good
        // infrastructure that must never enter the bypass flow.
        if (requestUrl != null && isWhitelistedHost(requestUrl)) return false
        // IP-block markers (NoveLA IP_BLOCKED_URL_MARKERS): the edge answered
        // with a hard IP ban (1020/1015) — no marker corroboration needed, and
        // no bypass will ever clear it. Detected as a challenge so the
        // failure path (fast-fail + terminal cooldown) runs instead of a
        // silent success.
        if (requestUrl != null && isIpBlockedUrl(requestUrl)) return true
        val options = cfOptions ?: CfOptions()
        // whitelist = true → skip detection entirely: this host is known-good.
        // NoveLA (CloudfareVerificationInterceptor.isNotCloudflare, where true
        // means "not a challenge, skip bypass"): `if
        // (domainOptions?.whitelist == true) return true` — the polarity is
        // inverted here because isChallenge returns true for "IS a challenge".
        if (options.whitelist) return false
        if (isChallengeByHeaders(status, serverHeader, cfMitigatedHeader)) return true
        val text = body?.let { lazy(it) } ?: return false
        // Filter out ignoreMarkers from the detection markers (NoveLA: activeMarkers = ALL_CF_MARKERS.filter { it !in ignoredMarkers })
        val activeMarkers = options.markers.filter { it !in options.ignoreMarkers }
        val hasMarkers = activeMarkers.any { text.value.contains(it, ignoreCase = true) }
        // Custom trigger markers fire on ANY status (NoveLA: checked after the
        // ERROR_CODES branch, so they corroborate an error status exactly like
        // a technical marker does).
        val hasTrigger = options.triggerMarkers.any { text.value.contains(it, ignoreCase = true) }
        // NoveLA parity (ERROR_CODES branch: `response.code in ERROR_CODES ->
        // hasMarkers && isCfServer`): a bare error status with no edge signal
        // and no body marker is a transient server error, not a challenge —
        // the status-retry layer owns it. The edge half already returned true
        // above, so only a marker can still corroborate the status here.
        return hasMarkers || hasTrigger
    }

    // Same predicate as isChallenge(), but names the branch that decided it.
    // Diagnostics only: the debug sink prints it next to status/headers, and
    // it must never influence request behaviour.
    fun challengeReason(
        status: Int,
        body: String,
        cfOptions: CfOptions?,
        serverHeader: String? = null,
        cfMitigatedHeader: String? = null,
        requestUrl: String? = null,
    ): String = challengeReason(status, { body }, cfOptions, serverHeader, cfMitigatedHeader, requestUrl)

    // Lazy twin of challengeReason. null body = metadata/URL branches only;
    // "none"/"status" then name the branch without decoding the body.
    fun challengeReason(
        status: Int,
        body: (() -> String)?,
        cfOptions: CfOptions?,
        serverHeader: String? = null,
        cfMitigatedHeader: String? = null,
        requestUrl: String? = null,
    ): String {
        if (requestUrl != null && hasStaticExtension(requestUrl)) return "static"
        if (requestUrl != null && isWhitelistedHost(requestUrl)) return "whitelist"
        if (requestUrl != null && isIpBlockedUrl(requestUrl)) return "ip-blocked"
        val options = cfOptions ?: CfOptions()
        if (options.whitelist) return "whitelist-option"
        if (cfMitigatedHeader.equals("challenge", ignoreCase = true)) return "cf-mitigated"
        val isCfServer = serverHeader?.let {
            it.contains("cloudflare", ignoreCase = true) || it.contains("ddos-guard", true)
        } == true
        if (isCfServer && status in EDGE_CHALLENGE_STATUSES) return "server-header"
        val text = body?.let { lazy(it) }
        val activeMarkers = options.markers.filter { it !in options.ignoreMarkers }
        if (text != null && activeMarkers.any { text.value.contains(it, ignoreCase = true) }) return "marker"
        if (text != null && options.triggerMarkers.any { text.value.contains(it, ignoreCase = true) }) return "trigger"
        if (status in options.statuses) return "status"
        return "none"
    }

    companion object {
        // Statuses where the CF edge header alone proves a challenge. 429 is
        // deliberately absent: "Server: cloudflare" is present on every
        // Cloudflare-fronted origin, and a bare 429 is usually the site's own
        // rate limit — solving a WebView there is useless and stalls the
        // request. A 429 still qualifies with explicit evidence: the
        // cf-mitigated: challenge header, a known body marker or a plugin
        // trigger marker.
        private val EDGE_CHALLENGE_STATUSES = setOf(403, 503)

        // NoveLA CloudfareVerificationInterceptor.STATIC_EXTENSIONS, verbatim.
        private val STATIC_EXTENSIONS = setOf(
            "js", "cjs", "mjs", "css", "scss", "sass", "less",
            "json", "jsonl", "xml", "yaml", "yml", "txt", "csv", "tsv",
            "lua", "wasm", "pdf", "map",
            "png", "jpg", "jpeg", "gif", "svg", "svgz", "webp", "ico",
            "bmp", "tiff", "tif", "avif", "heic", "heif", "psd", "ai",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus", "mid", "midi",
            "vtt", "ass", "srt", "ssa",
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "dmg",
        )

        // NoveLA CloudfareVerificationInterceptor.CLOUDFLARE_WHITELIST,
        // verbatim (github/raw = infrastructure, weserv/wsrv = image proxies
        // that forward the origin's challenge body as-is — see the NoveLA
        // comment on images.weserv.nl).
        private val BUILTIN_WHITELIST = listOf(
            "github.com",
            "raw.githubusercontent.com",
            "images.weserv.nl",
            "wsrv.nl",
        )

        // NoveLA CloudfareVerificationInterceptor.IP_BLOCKED_URL_MARKERS,
        // verbatim: hard IP-block signals (errors 1020/1015).
        private val IP_BLOCKED_URL_MARKERS = listOf(
            "/cdn-cgi/error/",
            "error=1020",
            "error=1015",
        )

        private fun pathExtension(url: String): String? {
            val path = url.substringAfter("://").substringAfter("/", "")
                .substringBefore("?").substringBefore("#")
            // NoveLA reads pathSegments.lastOrNull(); the last segment here.
            // No dot (or an empty path) means "no extension" — never static.
            val segment = path.substringAfterLast("/")
            if (segment.isEmpty() || '.' !in segment) return null
            return segment.substringAfterLast(".").lowercase()
        }

        private fun hasStaticExtension(url: String): Boolean =
            pathExtension(url)?.let { it in STATIC_EXTENSIONS } == true

        private fun urlHost(url: String): String =
            url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()

        // NoveLA: `if (CLOUDFLARE_WHITELIST.any { host.contains(it) }) return true`.
        private fun isWhitelistedHost(url: String): Boolean {
            val host = urlHost(url)
            return BUILTIN_WHITELIST.any { host.contains(it) }
        }

        private fun isIpBlockedUrl(url: String): Boolean =
            IP_BLOCKED_URL_MARKERS.any { url.contains(it, ignoreCase = true) }

        // Header-only view of isChallenge(), for call sites that cannot read the
        // body — the retry predicate runs before the body is available, and
        // reading it there would break streaming. Everything else (whitelist,
        // body markers, custom cfOptions.statuses) still needs isChallenge().
        fun isChallengeByHeaders(status: Int, serverHeader: String?, cfMitigatedHeader: String?): Boolean {
            // The CF edge names a challenge outright — trust it over any body heuristic.
            if (cfMitigatedHeader.equals("challenge", ignoreCase = true)) return true
            // A Cloudflare/DDoS-Guard edge answering with a challenge-prone status stays
            // a challenge even when the plugin's configured statuses exclude it.
            // 429 is excluded: it needs explicit evidence (see EDGE_CHALLENGE_STATUSES).
            val isCfServer = serverHeader?.let {
                it.contains("cloudflare", ignoreCase = true) || it.contains("ddos-guard", true)
            } == true
            return isCfServer && status in EDGE_CHALLENGE_STATUSES
        }
    }
}
