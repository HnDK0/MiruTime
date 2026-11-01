package dev.mirutime.core.network

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.SystemInfo
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.core.network.cf.CfOptions
import dev.mirutime.core.network.cf.CloudflareDetector
import dev.mirutime.core.network.cache.CacheKey
import dev.mirutime.core.network.cache.CachedResponse
import dev.mirutime.core.network.cache.ResponseCache
import dev.mirutime.core.network.security.BodySizeLimiter
import dev.mirutime.core.network.security.DnsFailureException
import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict
import dev.mirutime.core.network.security.transportViolation
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.replaceResponse
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.client.utils.EmptyContent
import io.ktor.util.ContentEncoder
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.Attributes
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

// Per-plugin HTTP client: default header pipeline (UA/Accept-Language), a
// manual CookieStore bridge (before request -> Cookie header; after response ->
// Set-Cookie written back, no HttpCookies plugin) and a CF challenge flow:
// detect -> gate (per-host lock + cooldown) -> solve+retry, iteratively up to
// MAX_CF_SOLVE_ATTEMPTS (=2, NoveLA MAX_MANUAL_ATTEMPTS) times.
// Optional SSRF guard (null = check off; host wiring decides later) and a
// streaming response-size limit applied where the body is read.
// Redirects are followed by hand (default on): every hop is re-validated by the
// SSRF guard before the next request goes out — see RedirectFollower.kt.
// Siblings: CallDeadline.kt (whole-call deadline), RedirectFollower.kt (manual
// 3xx walking), PluginHttpClientFactory.kt (platform engine selection),
// cache/ResponseCache.kt (optional host-owned GET cache, NoveLA DiskCache).

// Platform ContentEncoder for Brotli (br) response bodies; the actual lives in
// jvmSharedMain (pure-Java org.brotli:dec decoder, no JNI) and is shared by the
// jvm and android compilations. commonMain cannot see jvmSharedMain directly,
// so it is declared expect/actual — next to its only use, rather than in a
// second BrotliEncoder.kt. internal: it is plumbing for this client only.
internal expect val BrotliEncoder: ContentEncoder

// Ktor's built-in gzip decoder rejects RFC 1952 headers carrying FNAME
// ("Gzip file name not supported"). Static servers routinely store the original
// filename in pre-compressed .gz/.js responses (hitomi's gg.js does), so gzip is
// decoded through a tolerant JDK GZIPInputStream instead.
internal expect val GzipEncoder: ContentEncoder

class PluginHttpClient(
    private val metadata: PluginMetadata,
    private val systemInfo: SystemInfo,
    private val cookieStore: CookieStore,
    private val logger: Logger,
    engine: HttpClientEngine,
    private val detector: CloudflareDetector = CloudflareDetector(),
    private val gate: CfGate = CfGate(),
    private val solver: CfChallengeSolver? = null,
    private val ssrfGuard: SsrfGuard? = null,
    private val maxResponseBytes: Long = 25L * 1024 * 1024,
    // Per-host override registry (CF options, UA, Referer): registered at
    // plugin install / engine load, cleared on dispose.
    private val hostOptions: HostOptionsRegistry? = null,
    // Optional response cache (NoveLA DiskCache parity). LIFECYCLE BELONGS TO THE
    // HOST: create one ResponseCache per process, share it across clients, and
    // call invalidateBySource()/clear() from your own code — this client only
    // reads and writes. null (the default) = caching off, which is what every
    // existing caller gets, so no behaviour changes until a host opts in.
    // The host MUST pass the SAME clock source to this client and to the cache:
    // storedAt is stamped here and TTL is validated there, so two different
    // epochs expire every entry immediately (or never).
    private val cache: ResponseCache? = null,
    // Whole-call deadline for calls WITHOUT an explicit timeoutMs. NoveLA wraps
    // every http_get/http_post in withTimeout(90_000) (NetworkClient.kt:79/:180)
    // because its retry structure is multiplicative and CF solving alone can
    // take 50s. null disables it. Enforced as boundary CHECKS, never as a
    // coroutine timer — see CallDeadline.
    private val defaultDeadlineMs: Long? = 90_000,
    // Injectable monotonic clock, same shape as CfGate.clock. Tests advance a
    // counter by hand instead of waiting out a real 90s.
    private val clock: () -> Long = monotonicClockMs(),
    // Optional diagnostic observer, emitted once per network attempt after the
    // body is read (see NetworkTraceEvent). null (default) is the app path and
    // must stay free: no field is computed, no detection pass runs. Wiring is
    // host-side only — LuaPluginSourceFactory passes it straight through.
    private val trace: ((NetworkTraceEvent) -> Unit)? = null,
    // Fresh-network scope driven by the adapter (NoveLA: luaEngine
    // .withForceNetwork around getCatalogList/getCatalogFiltered): when active,
    // the response cache is skipped but still refreshed. The explicit
    // forceNetwork parameter wins per call; this is the ambient equivalent for
    // calls the adapter makes on the plugin's behalf.
    private val forceNetworkActive: (() -> Boolean)? = null,
    // Per-plugin-session diagnostic label (Lua entry point + argument names);
    // null for the host client. Read only by the trace formatter.
    private val callContext: NetworkCallContext? = null,
    // Redirect hops this client may follow (initial request not counted).
    // 20 is the browser-shaped default; a bounded lane (repo/update, images)
    // passes a smaller value through its own client instead of a per-call knob.
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    private val requestBuilder = PluginRequestBuilder(systemInfo, metadata.userAgentPreset)
    private val bodyLimiter = BodySizeLimiter(maxResponseBytes)
    private val retryLog: (String) -> Unit = { message -> logger.d("net", message) }
    private val redirectLog: (String) -> Unit = retryLog
    private val client = HttpClient(engine) {
        // Engine-independent transport timeouts: NoveLA ran 10s for connect and
        // read, while CIO defaults to 15s for the request phase and OkHttp to
        // 10s per phase — without this the behaviour depends on the engine.
        // requestTimeoutMillis stays unset: the whole-call budget in
        // withCallBudget owns the request deadline, across every redirect hop.
        // The retry ladder (sendRequest) overrides these per attempt.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        // Transparently decode gzip/deflate/br response bodies. Ktor core does NOT
        // decompress; without this a compressed body arrives raw. Registered from
        // the same list that drives Accept-Encoding in PluginRequestBuilder, so
        // the two can never drift apart.
        install(ContentEncoding) {
            SUPPORTED_CONTENT_ENCODINGS.forEach { encoding ->
                when (encoding) {
                    "gzip" -> customEncoder(GzipEncoder)
                    "deflate" -> deflate()
                    "br" -> customEncoder(BrotliEncoder)
                    else -> error("Unsupported content encoding: $encoding")
                }
            }
        }
        // Transport resilience (NoveLA retried IOException for GETs): the
        // TransportRetryLadder Send-loop, not HttpRequestRetry — Ktor reuses
        // one built request for every plugin retry, so per-attempt timeouts
        // cannot ride the plugin config (see TransportRetryLadder.kt).
        // Status retries stay with ServerErrorRetryInterceptor so a 503 is
        // never retried on two axes at once.
        // Status backoff retries (429/502/503/504), after the CF challenge check
        // in shouldRetry so a challenge never consumes an attempt.
        install(TransportRetryLadder)
        install(ServerErrorRetryInterceptor)
        // Redirects are followed manually in fetchFollowingRedirects so the SSRF
        // guard can re-validate EVERY hop (Ktor's built-in follower has no hook).
        followRedirects = false
    }

    suspend fun get(
        sourceId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        // forceNetwork = NoveLA's "no-cache": skip the cached copy but still
        // refresh it from the network afterwards.
        forceNetwork: Boolean = false,
        followRedirects: Boolean = true,
        method: String = "GET",
        // Diagnostic labels (Lua entry point / argument names); values never.
        operation: String? = null,
        argNames: Collection<String> = emptyList(),
        // Images opt out: a detected challenge is returned as-is, without the
        // per-host gate, the solver or a retry (NoveLA never solves covers).
        solveChallenges: Boolean = true,
    ): HttpResponse {
        // Diagnostics only: never log full URLs or query strings.
        logger.d("PluginHttpClient", "$method ${safeHostPath(url)} (source=$sourceId)")
        return requestWithCfHandling(
            sourceId, method, url, headers, timeoutMs, followRedirects, null, null, forceNetwork,
            operation, argNames, solveChallenges,
        )
    }

    suspend fun post(
        sourceId: String,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        followRedirects: Boolean = true,
        contentType: String? = null,
        operation: String? = null,
        argNames: Collection<String> = emptyList(),
        solveChallenges: Boolean = true,
    ): HttpResponse {
        logger.d("PluginHttpClient", "POST ${safeHostPath(url)} (source=$sourceId)")
        // POST is never cached (cacheKeyFor is GET-only).
        return requestWithCfHandling(
            sourceId, "POST", url, headers, timeoutMs, followRedirects, body, contentType, forceNetwork = false,
            operation = operation, argNames = argNames, solveChallenges = solveChallenges,
        )
    }

    // Shared GET/POST path: cache read -> SSRF gate -> request (+redirects) ->
    // CF detect/solve/retry -> cache write.
    private suspend fun requestWithCfHandling(
        sourceId: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long?,
        followRedirects: Boolean,
        body: String?,
        contentType: String?,
        forceNetwork: Boolean,
        operation: String?,
        argNames: Collection<String>,
        solveChallenges: Boolean,
    ): HttpResponse {
        // Transport policy runs on the raw URL first: a javascript:/malformed
        // target must surface as an SSRF block, not as Ktor's URLParserException
        // from the host lookup below. The policy runs for every client, guard
        // or not (plain translation/repo/image clients have ssrfGuard=null).
        checkSsrf(url)
        // Normalized once: options lookup, gate locks, cooldowns and
        // CfChallenge.host all share the HostOptionsRegistry key scheme.
        val host = normalizeHost(Url(url).host)

        // GET-only (null for POST) and null when no cache is wired.
        val cacheKey = cacheKeyFor(sourceId, method, url, headers)
        // NoveLA withForceNetwork (catalog paths): skip the cached copy but
        // still refresh it from the network afterwards.
        val force = forceNetwork || forceNetworkActive?.invoke() == true
        // Lua entry point / argument NAMES only; values (query, title) never.
        val tracePlugin = operation ?: callContext?.operation() ?: "-"
        val traceArgs = if (argNames.isNotEmpty()) {
            argNames.joinToString(",")
        } else {
            callContext?.argNames() ?: "-"
        }
        val callStartedAt = clock()
        logger.d(
            "net",
            "event=cache state=${if (cacheKey == null) "off" else if (force) "force" else "lookup"} " +
                "url=${safeHostPath(url)} source=$sourceId",
        )
        if (cacheKey != null && !force) {
            val cached = cache?.get(cacheKey)
            if (cached != null) {
                logger.d("net", "event=cache state=hit url=${safeHostPath(url)} source=$sourceId")
                return cachedResponse(cached, url)
            }
            logger.d("net", "event=cache state=miss url=${safeHostPath(url)} source=$sourceId")
        }

        // One budget for the WHOLE call: every redirect hop, the status-retry
        // backoff and the CF bypass all draw from it — not the per-hop timeout
        // Ktor gives you (NoveLA's BudgetedCall semantics). Captured ONCE here
        // so every boundary below reads the same start instant.
        val deadline = CallDeadline(
            clock,
            callStartedAt,
            if (timeoutMs == null) defaultDeadlineMs else null,
        )
        // One sender for both passes (initial + post-solver retry). The
        // per-call plumbing lives in this closure; fetchFollowingRedirects only
        // sees what varies per hop.
        // hopIndex is reset before EACH fetch pass, so a post-solver retry
        // reports its hops as 0,1,... again instead of continuing a run counter.
        var hopIndex = 0
        val sendHop: SendHop = { hopMethod, hopUrl, hopHeaders, hopBody, hopUserAgent ->
            val hopStartedAtMs = clock()
            val hop = sendRequest(
                hopMethod, hopUrl, hopHeaders, timeoutMs, deadline, host, hopBody, contentType, hopUserAgent,
            )
            val hopBytes = bodyLimiter.readWithinLimit(hop.bodyAsChannel())
            val hopNumber = hopIndex++
            emitTrace(
                hopStartedAtMs, hopMethod, hopUrl, hop, hopBytes, hopNumber,
                solveChallenges, tracePlugin, traceArgs,
            )
            hop to hopBytes
        }
        return withCallBudget(url, timeoutMs) {
            hopIndex = 0
            val firstPass = fetchFollowingRedirects(
                sendHop, ::checkSsrf, deadline, method, url, headers, followRedirects, body,
                onRedirect = redirectLog, maxRedirects = maxRedirects,
            )
            var response = firstPass.first
            var bytes = firstPass.second
            // Decoded lazily and at most once per response: detection, marker
            // lookup and the diagnostic trigger share this one value. A client
            // that cannot solve returns a challenge as-is, so its success
            // bodies are never decoded for a marker scan.
            var bodyText = lazy { bytes.decodeToString() }
            var scanBody = shouldScanBody(response, host, solveChallenges)
            var bodyProvider: (() -> String)? = if (scanBody) ({ bodyText.value }) else null
            val challenged = isChallenge(response, bodyProvider, host)
            // Diagnostics only (decision + warn line below): the solver gets the
            // markers of the response it is solving, recomputed per iteration.
            // A no-solve call never needs them.
            val firstMarkers =
                if (challenged && solveChallenges) matchedMarkers(host, bodyText.value) else emptyList()
            val cfMitigated = response.headers[CF_MITIGATED].equals("challenge", ignoreCase = true)
            if (!challenged) {
                logger.d(
                    "net",
                    "event=cf verdict=none hidden=skipped visible=no " +
                        "trigger=${challengeReason(response, bodyProvider, host)} " +
                        "status=${response.status.value} url=${safeHostPath(url)}",
                )
                storeIfCacheable(cacheKey, response.status.value, response.headers, bytes)
                logFinalNet(method, url, response, hopIndex, callStartedAt, tracePlugin)
                return@withCallBudget rebuildResponse(response, bytes)
            }

            // Bare 429 = the origin's rate limit, not a browser check: the
            // hidden phase is skipped exactly like TwoPhaseCfSolver does.
            val hidden = when {
                response.status.value == 429 && firstMarkers.isEmpty() && !cfMitigated -> "skipped"
                solver != null -> "ran"
                else -> "skipped"
            }
            // Diagnostics only: computed once per challenge, after the early
            // return, from the same lazy body (no second decode).
            val trigger = challengeReason(response, bodyProvider, host)
            logger.d(
                "net",
                "event=cf verdict=challenge hidden=$hidden visible=no " +
                    "trigger=$trigger " +
                    "status=${response.status.value} url=${safeHostPath(url)}",
            )
            logger.w(
                "PluginHttpClient",
                "CF challenge on $host (status=${response.status.value}, " +
                    "server=${response.headers[HttpHeaders.Server] ?: "-"}, " +
                    "cfMitigated=${response.headers[CF_MITIGATED] ?: "-"}, " +
                    "markers=${firstMarkers.joinToString("|").ifEmpty { "none" }})",
            )
            if (!solveChallenges) {
                logFinalNet(method, url, response, hopIndex, callStartedAt, tracePlugin)
                return@withCallBudget rebuildResponse(response, bytes)
            }
            // Effective Referer the request will carry (caller header or the
            // scheme://host/ default) — NoveLA reads originalRequest's Referer
            // the same way (its defaultHeaders always set one).
            val referer = headers.entries
                .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                ?: refererFromUrl(url)
            // One predicate decides both the solver target and the error fields.
            val isXhr = isXhrRequest(method, url, headers)
            val challengeError = {
                CfChallengeRequiredException(
                    PluginError.CfChallengeRequired(
                        host = host,
                        url = url,
                        referer = referer,
                        status = response.status.value,
                        isXhr = isXhr,
                    ),
                )
            }
            val solver = solver ?: throw challengeError()

            // A challenge that arrived WHILE cf_clearance was attached means the
            // clearance genuinely expired — NoveLA re-solves in that case even if
            // a success cooldown is active. Heuristic ceiling: the store may also
            // hold a cf_clearance written by the challenge response itself (via
            // Set-Cookie on this very fetch), which would false-positive here;
            // the gate's host mutex + failure cooldown still bound the damage.
            val expiredClearance = cookieStore.get(url).any { it.name == "cf_clearance" }
            try {
                gate.withHostLock(host, expiredClearance = expiredClearance) {
                    // NoveLA parity (MAX_MANUAL_ATTEMPTS=2): up to TWO solve+retry
                    // rounds inside ONE host lock. Iterative, never recursive — the
                    // third challenge fails hard and the gate's failure cooldown keeps
                    // every later request to this host on the fast path.
                    var attempts = 0
                    while (isChallenge(response, bodyProvider, host)) {
                        if (++attempts > MAX_CF_SOLVE_ATTEMPTS) {
                            gate.enterCooldown(host, success = false)
                            throw challengeError()
                        }
                        // NoveLA clears ONLY cf_clearance before a fresh manual
                        // bypass (clearCookiesForDomain, Max-Age=0): a clearance CF
                        // just rejected must not be re-sent with the next solve.
                        // Non-CF cookies (sort order, locale) survive — the server
                        // re-creates only what the selective delete removed.
                        if (attempts > 1) {
                            cookieStore.clearCookies(host, listOf("cf_clearance"))
                            logger.w(
                                "PluginHttpClient",
                                "CF attempt $attempts/$MAX_CF_SOLVE_ATTEMPTS on $host (cf_clearance cleared)",
                            )
                        }
                        // CF solving alone may take 50s in NoveLA — check on both sides
                        // so a solver that blows the budget stops here, not on hop 2.
                        deadline.throwIfExpired(url)
                        val solution = solver.solve(
                            CfChallenge(
                                host = host,
                                url = url,
                                status = response.status.value,
                                markers = matchedMarkers(host, bodyText.value),
                                referer = referer,
                                userAgent = effectiveUserAgent(host, headers),
                                isXhr = isXhr,
                                // Explicit CF evidence separates a challenge 429 from
                                // the origin's own bare rate limit.
                                cfMitigated = response.headers[CF_MITIGATED]
                                    .equals("challenge", ignoreCase = true),
                            ),
                        )
                        deadline.throwIfExpired(url)
                        if (solution == null) {
                            gate.enterCooldown(host, success = false)
                            throw challengeError()
                        }
                        if (solution.cookies.isNotEmpty()) {
                            // The solver reports name=value only; a clearance is
                            // host-wide, so pin Path=/ (Secure on https) instead
                            // of inheriting the challenged request path.
                            val secure = url.startsWith("https://", ignoreCase = true)
                            cookieStore.setFromResponse(
                                url,
                                solution.cookies.map { (name, value) ->
                                    if (secure) {
                                        "$name=$value; Path=/; Secure"
                                    } else {
                                        "$name=$value; Path=/"
                                    }
                                },
                            )
                        }
                        // Retry with the solver's cookies AND user agent. Set-Cookie is
                        // bridged on EVERY hop: sendRequest runs the cookie bridge per
                        // request, so intermediate redirect responses reach the store
                        // as well — not only the final one.
                        hopIndex = 0
                        val retry = fetchFollowingRedirects(
                            sendHop, ::checkSsrf, deadline, method, url, headers, followRedirects, body,
                            solution.userAgent, onRedirect = redirectLog, maxRedirects = maxRedirects,
                        )
                        response = retry.first
                        bytes = retry.second
                        // New response body: re-arm the lazy decode and the
                        // scan decision for the next isChallenge() check.
                        bodyText = lazy { bytes.decodeToString() }
                        scanBody = shouldScanBody(response, host, solveChallenges)
                        bodyProvider = if (scanBody) ({ bodyText.value }) else null
                    }
                    gate.enterCooldown(host, success = true)
                    storeIfCacheable(cacheKey, response.status.value, response.headers, bytes)
                    logFinalNet(method, url, response, hopIndex, callStartedAt, tracePlugin)
                    rebuildResponse(response, bytes)
                }
            } catch (e: CfChallengeRequiredException) {
                // The gate's cooldown/lock-wait fast-fail carries only the host.
                // Rebuild it from this request so the UI can offer Solve for the
                // same operation; a typed error from the block keeps its fields.
                if (e.error.url != null) throw e
                throw challengeError()
            }
        }
    }

    // NoveLA bounded the WHOLE call rather than each hop: an explicit timeoutMs
    // is the caller's budget, and it must span every redirect hop, the
    // status-retry backoff and the CF bypass — a per-hop `timeout {}` re-arms on
    // each hop, so N hops silently multiply the caller's budget by N.
    // An exhausted budget surfaces as Ktor's HttpRequestTimeoutException — the
    // type callers already map to "Timeout" — instead of escaping as a raw
    // coroutine cancellation.
    // Calls WITHOUT timeoutMs are bounded by CallDeadline's boundary checks
    // instead (see newCallDeadline): a real coroutine timer here would arm at
    // t=0 and runTest's virtual scheduler would fast-forward straight to it
    // before the engine ever starts — that is exactly what broke 27 tests.
    private suspend fun <T> withCallBudget(url: String, timeoutMs: Long?, block: suspend () -> T): T {
        if (timeoutMs == null) return block()
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw HttpRequestTimeoutException(url, timeoutMs, e)
        }
    }

    // URL-only on every platform: hostname -> address checks live in the
    // engine's SafeDns hook (Android). The JVM engine (CIO) has no DNS hook, so
    // JVM hosts run URL rules only and are not a security boundary — see the
    // jvmMain factory. isSafe never resolves DNS, so a working fetch is never
    // rejected as a false DnsFailure.
    // The transport policy is NOT optional: even a client with ssrfGuard=null
    // (translation, repo/update, images) refuses a non-http(s) hop or a URL
    // carrying userinfo before any request. A malformed URL is reported as an
    // SSRF block here instead of escaping as a Ktor parser exception.
    private fun checkSsrf(url: String) {
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            logger.d("net", "event=ssrf url=${safeHostPath(url)} verdict=blocked")
            throw SsrfBlockedException("malformed url")
        }
        transportViolation(parsed)?.let { reason ->
            logger.d("net", "event=ssrf url=${safeHostPath(url)} verdict=blocked")
            throw SsrfBlockedException(reason)
        }
        val guard = ssrfGuard ?: return
        when (val verdict = guard.isSafe(url)) {
            is SsrfVerdict.Blocked -> {
                logger.d("net", "event=ssrf url=${safeHostPath(url)} verdict=blocked")
                throw SsrfBlockedException(verdict.reason)
            }
            // Not produced by isSafe; kept for the sealed contract.
            SsrfVerdict.DnsFailure -> {
                logger.d("net", "event=ssrf url=${safeHostPath(url)} verdict=dns-failure")
                throw DnsFailureException(normalizeHost(parsed.host))
            }
            SsrfVerdict.Ok ->
                logger.d("net", "event=ssrf url=${safeHostPath(url)} verdict=ok")
        }
    }

    private suspend fun sendRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long?,
        deadline: CallDeadline,
        host: String,
        body: String?,
        contentType: String?,
        userAgentOverride: String?,
    ): HttpResponse {
        val cookies = cookieStore.get(url)
        return client.request(url) {
            this.method = when (method) {
                "HEAD" -> HttpMethod.Head
                "POST" -> HttpMethod.Post
                else -> HttpMethod.Get
            }
            // Order: bridged cookies first, then defaults + caller extras via
            // headers.set — a caller-provided Cookie header wins over the bridge.
            if (cookies.isNotEmpty()) {
                this.headers.set(
                    HttpHeaders.Cookie,
                    cookies.joinToString("; ") { "${it.name}=${it.value}" },
                )
            }
            // Per-host UA override (registered at install, cleared on dispose)
            // WINS over the plugin's static metadata.userAgentPreset; a CF
            // solution's UA (retry path) is the strongest signal — applied last
            // via userAgentOverride. Passed into applyTo so the sec-ch-ua*
            // family is derived from the UA actually sent, never from a
            // contradicting preset.
            // Per-host Referer override (the plugin's `referer` global,
            // NoveLA SourceInterface.referer): applies only when the caller
            // sent no explicit Referer.
            requestBuilder.applyTo(
                this,
                headers,
                userAgentOverride ?: hostOptions?.userAgent(host),
                hostOptions?.referer(host),
            )
            if (body != null) {
                setBody(TextContent(body, ContentType.parse(contentType ?: "text/plain")))
            }
            // Both retry plugins read the whole-call deadline from here, the
            // same way they read the budget flag below.
            attributes.put(CallDeadlineAttributeKey, deadline)
            attributes.put(RetryLogAttributeKey, retryLog)
            if (timeoutMs != null) {
                // Whole-call budget active: exactly one attempt, no backoff
                // (NoveLA BudgetedCall) — read by ServerErrorRetryInterceptor.
                // The deadline itself comes from withCallBudget, NOT from a
                // per-hop timeout{}: re-arming per hop would hand each hop its
                // own full budget, so N hops multiply the caller's by N.
                attributes.put(BudgetedCallAttributeKey, true)
            }
        }.also { response ->
            val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
            if (!setCookies.isNullOrEmpty()) {
                cookieStore.setFromResponse(url, setCookies)
            }
        }
    }

    // bodyAsBytes consumed the original channel — re-wrap so callers can
    // still read the body of a non-challenge response.
    private fun rebuildResponse(response: HttpResponse, bytes: ByteArray): HttpResponse =
        response.call.replaceResponse { ByteReadChannel(bytes) }.response

    private fun isChallenge(response: HttpResponse, body: (() -> String)?, host: String): Boolean =
        detector.isChallenge(
            response.status.value,
            body,
            cfOptionsFor(host),
            response.headers[HttpHeaders.Server],
            response.headers[CF_MITIGATED],
            // NoveLA checks response.request.url (the final hop after
            // redirects): drives the static-asset skip, the built-in host
            // whitelist and the IP-block markers. Without it those three
            // never fire on the real path.
            requestUrl = response.request.url.toString(),
        )

    // A body scan is only useful when the client can act on the verdict
    // (solveChallenges) or the status is one the plugin treats as
    // challenge-prone. Everything else is decided by URL/header metadata,
    // so a 20 MB success body on an image/repo client is never decoded.
    // The detector still runs the full marker logic whenever it gets a body.
    private fun shouldScanBody(response: HttpResponse, host: String, solveChallenges: Boolean): Boolean =
        solveChallenges || response.status.value in (cfOptionsFor(host) ?: CfOptions()).statuses

    // Debug-only: which detector branch fired (static/whitelist/server-header/
    // cf-mitigated/marker/trigger/status/none). Never carries body text.
    private fun challengeReason(response: HttpResponse, body: (() -> String)?, host: String): String =
        detector.challengeReason(
            response.status.value,
            body,
            cfOptionsFor(host),
            response.headers[HttpHeaders.Server],
            response.headers[CF_MITIGATED],
            requestUrl = response.request.url.toString(),
        )

    // Final per-call line: redirects actually walked, the last URL, success
    // and whole-call elapsed. No query/fragment (safeHostPath).
    private fun logFinalNet(
        method: String,
        url: String,
        response: HttpResponse,
        hopIndex: Int,
        startedAtMs: Long,
        plugin: String,
    ) {
        logger.d(
            "net",
            "event=net method=$method url=${safeHostPath(url)} " +
                "finalUrl=${safeHostPath(response.request.url.toString())} " +
                "status=${response.status.value} redirects=${(hopIndex - 1).coerceAtLeast(0)} " +
                "pluginSuccess=${response.status.value in 200..299} plugin=$plugin " +
                "elapsed=${clock() - startedAtMs}ms",
        )
    }

    // Diagnostic observer: returns immediately when no sink is wired, so the
    // app path never pays for header flattening or the second detector pass.
    // Ceiling: a hop whose send throws (timeout/SSRF/DNS) produces no event —
    // there is no body to report, and the exception already names the failure.
    private suspend fun emitTrace(
        startedAtMs: Long,
        method: String,
        url: String,
        response: HttpResponse,
        bytes: ByteArray,
        hop: Int,
        solveChallenges: Boolean,
        plugin: String,
        args: String,
    ) {
        val sink = trace ?: return
        val traceHost = normalizeHost(Url(url).host)
        // Same lazy rule as the request path: a diagnostic observer on a
        // no-solve client never turns a large body into a marker scan.
        val traceBody = lazy { bytes.decodeToString() }
        val traceProvider: (() -> String)? =
            if (shouldScanBody(response, traceHost, solveChallenges)) ({ traceBody.value }) else null
        val cfBlocked = detector.isChallenge(
            response.status.value,
            traceProvider,
            cfOptionsFor(traceHost),
            response.headers[HttpHeaders.Server],
            response.headers[CF_MITIGATED],
            requestUrl = response.request.url.toString(),
        )
        // Counts only: CookieManager exposes no per-cookie domain list, so the
        // applied header is the one source (cookiesStored mirrors it). Values
        // never leave the store.
        val applied = cookieStore.get(url)
        sink(
            NetworkTraceEvent(
                method = method,
                // host+path only: a query/fragment must never reach a trace
                // consumer (plugin-tester, debug log) even though the event is
                // in-memory.
                url = safeHostPath(url),
                status = response.status.value,
                // Header VALUES are redacted at the event boundary: the tester
                // renders this map, so hiding secrets only in the formatter
                // would leave a second leak path.
                requestHeaders = redactRequestHeaders(
                    response.request.headers.entries()
                        .associate { (name, values) -> name to values.joinToString(", ") },
                ),
                // Lowercased: NetworkBindings hands plugins lowercase response
                // header names, and the tester reads them the same way.
                responseHeaders = redactResponseHeaders(buildMap {
                    for (name in response.headers.names()) {
                        val key = name.lowercase()
                        put(key, get(key).orEmpty() + response.headers.getAll(name).orEmpty())
                    }
                }),
                bodyBytes = bytes.size.toLong(),
                durationMs = clock() - startedAtMs,
                cfBlocked = cfBlocked,
                redirectHop = hop,
                cfTrigger = if (cfBlocked) {
                    challengeReason(response, traceProvider, traceHost)
                } else {
                    null
                },
                plugin = plugin,
                args = args,
                cookiesStored = applied.size,
                cookiesApplied = applied.size,
                cfClearance = applied.any { it.name == "cf_clearance" },
            ),
        )
    }

    // Redaction policy for the trace event (mirrors NetworkTraceLog):
    //  - Cookie/Set-Cookie -> names + present, never values;
    //  - Authorization/Proxy-Authorization -> redacted;
    //  - User-Agent -> present;
    //  - Referer/Location -> host+path (no query);
    //  - everything else is diagnostic-safe.
    private fun redactRequestHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            when (name.lowercase()) {
                "cookie", "set-cookie" -> cookieNames(value)
                "authorization", "proxy-authorization" -> "redacted"
                "user-agent" -> "present"
                "referer" -> safeHostPath(value)
                else -> value
            }
        }

    private fun redactResponseHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            when (name.lowercase()) {
                "set-cookie", "cookie" -> listOf("present")
                "location", "content-location" -> values.map(::safeHostPath)
                else -> values
            }
        }

    private fun cookieNames(value: String): String {
        val names = value.split(';', ',')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (names.isEmpty()) "present" else names.joinToString("; ") { "$it=present" }
    }

    private fun matchedMarkers(host: String, body: String): List<String> {        val markers = cfOptionsFor(host)?.markers ?: CfOptions().markers
        return markers.filter { body.contains(it, ignoreCase = true) }
    }

    // The UA the request pipeline will actually send (PluginRequestBuilder
    // precedence): caller header > per-host override > preset/default. [host]
    // is already normalized. The solver's WebView must present this exact
    // value and the post-solve retry reuses it, so cf_clearance is never bound
    // to a different browser fingerprint.
    private fun effectiveUserAgent(host: String, headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals(HttpHeaders.UserAgent, ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
            ?: hostOptions?.userAgent(host)
            ?: requestBuilder.resolveUserAgent(host)

    // Per-host override (registered at plugin install, cleared on dispose)
    // WINS over this plugin's static metadata.cfOptions.
    private fun cfOptionsFor(host: String): CfOptions? =
        hostOptions?.cfOptions(host) ?: metadata.cfOptions

    // GET-only: a POST body is never replayed from cache. Returns null when no
    // cache is wired, which keeps the whole path a no-op for existing callers.
    // Key = method + url + sourceId + the headers that can change the body:
    // the caller's own set (hashed by CacheKey) plus the two WE resolve per call
    // — the effective per-host UA and the Accept-Encoding list — so a per-host
    // UA override or an encoder change can never serve a stale body.
    // ceiling: no Vary handling — a server varying on other headers may serve a
    // body that ignores them; charset is unused because we store raw bytes.
    private fun cacheKeyFor(sourceId: String, method: String, url: String, headers: Map<String, String>): CacheKey? {
        if (cache == null || !method.equals("GET", ignoreCase = true)) return null
        val effective = headers + mapOf(
            HttpHeaders.UserAgent to requestBuilder.resolveUserAgent(runCatching { normalizeHost(Url(url).host) }
                .getOrDefault("")),
            HttpHeaders.AcceptEncoding to ACCEPT_ENCODING,
        )
        return CacheKey.from("GET", url, null, sourceId, effective)
    }

    // Only a clean, non-challenge 200 is worth keeping; a 3xx/4xx/5xx or a CF
    // challenge must revalidate against the origin next time.
    private fun storeIfCacheable(key: CacheKey?, status: Int, headers: Headers, bytes: ByteArray) {
        val target = cache ?: return
        if (key == null || status != 200) return
        val stored = buildMap {
            headers.forEach { name, values -> put(name.lowercase(), values) }
        }
        target.put(key, CachedResponse(status, stored, bytes, clock()))
    }

    // Rebuilds a cached entry as a real HttpResponse without touching the
    // engine — the same HttpRequestData/HttpResponseData pair MockEngine uses.
    // The body was stored AFTER ContentEncoding decoded it, so no plugin runs
    // here: what comes back is byte-identical to what the network produced.
    // Ktor marks those data classes @InternalAPI: opting in is the documented
    // escape hatch and is exactly what MockEngine does internally.
    @OptIn(InternalAPI::class)
    private suspend fun cachedResponse(entry: CachedResponse, url: String): HttpResponse {
        val call = HttpClientCall(
            client,
            HttpRequestData(
                url = Url(url),
                method = HttpMethod.Get,
                headers = headersOf(),
                // A GET carries no body; Ktor's own EmptyContent is the null
                // object for a non-null OutgoingContent slot.
                body = EmptyContent,
                executionContext = Job(),
                attributes = Attributes(),
            ),
            HttpResponseData(
                statusCode = HttpStatusCode.fromValue(entry.status),
                requestTime = GMTDate(),
                headers = headersOf(*entry.headers.map { (name, values) -> name to values }.toTypedArray()),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(entry.body),
                callContext = coroutineContext,
            ),
        )
        return call.response
    }
}

internal const val CF_MITIGATED = "cf-mitigated"

// CF solve+retry rounds allowed inside ONE request (NoveLA
// CloudfareVerificationInterceptor.MAX_MANUAL_ATTEMPTS = 2): the first bypass
// plus one fresh re-solve after the rejected clearance. Iterative bound — the
// third challenge fails hard, never loops.
private const val MAX_CF_SOLVE_ATTEMPTS = 2

// Single source of truth for response compression: PluginHttpClient registers
// exactly these encoders in ContentEncoding and PluginRequestBuilder advertises
// exactly this list in Accept-Encoding — one list, so the two cannot drift.
// Parity: the wire value stays "gzip, deflate, br" (NoveLA).
internal val SUPPORTED_CONTENT_ENCODINGS: List<String> = listOf("gzip", "deflate", "br")
internal val ACCEPT_ENCODING: String = SUPPORTED_CONTENT_ENCODINGS.joinToString(", ")

// XHR / no-JS-context markers. Exactly these four (NoveLA parity): a plain
// non-GET is a browser NAVIGATION — a form POST gets a real page with a JS
// context, so it must load its own URL, not the Referer. Origin alone does not
// make a request XHR either.
@Suppress("UNUSED_PARAMETER") // method stays for symmetry; non-GET is no longer a marker
internal fun isXhrRequest(method: String, url: String, headers: Map<String, String>): Boolean {
    if ("/admin-ajax.php" in url || "/api/" in url) return true
    return headers.entries.any { (name, value) ->
        name.equals("X-Requested-With", ignoreCase = true) ||
            (name.equals("Accept", ignoreCase = true) && value.contains("json", ignoreCase = true))
    }
}
