package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.core.network.cf.CfOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginHttpClientCfTest {

    private val systemInfo = FakeSystemInfo()
    private val cookieStore = FakeCookieStore()
    private val logger = RecordingLogger()

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private class FakeSolver(
        private val result: CfSolution?,
        private val delayMs: Long = 0,
    ) : CfChallengeSolver {
        var calls = 0
        val requests = mutableListOf<CfChallenge>()

        override suspend fun solve(request: CfChallenge): CfSolution? {
            calls++
            requests += request
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    // A CF challenge is not a transient server error: the retry layer must hand
    // the FIRST response straight to the detector instead of burning up to 4
    // backoff attempts (~7.5s) against the challenge endpoint. NoveLA keeps its
    // CF interceptor outside the retry layer for the same reason.
    @Test
    fun cfChallenge503DoesNotConsumeRetryAttempts() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond(
                    content = "<html>Just a moment...</html>",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.Server, "cloudflare"),
                )
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/page")

        assertEquals(200, response.status.value)
        // 1 challenge + 1 post-CF retry — no backoff attempt was burned.
        assertEquals(2, engineCalls)
        assertEquals(1, solver.calls)
    }

    // A 429 carrying `cf-mitigated: challenge` is a browser check: it reaches
    // the solver on the FIRST response with the explicit evidence attached, so
    // TwoPhaseCfSolver may run the hidden phase. A bare 429 never gets here
    // (the detector does not flag it; the status ladder owns it).
    @Test
    fun cfMitigated429IsPassedToSolverAsCfMitigated() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond(
                    content = "<html>Just a moment...</html>",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(CF_MITIGATED, "challenge"),
                )
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/page")

        assertEquals(200, response.status.value)
        assertEquals(2, engineCalls)
        val challenge = solver.requests.single()
        assertEquals(429, challenge.status)
        assertTrue(challenge.cfMitigated, "cf-mitigated: challenge must be carried to the solver")
    }

    // Stale-marker regression: the solver must receive the markers of the
    // response it is actually solving. The retry's only CF evidence here is
    // the plugin's trigger marker — it matches no entry of `markers` — so the
    // solver sees a bare 429 (empty markers, no cf-mitigated) and skips the
    // hidden phase instead of spending 15s on the FIRST body's marker.
    // POST: the status ladder is GET-only, so the 429 reaches the CF loop
    // as-is instead of being retried into a 200 first.
    @Test
    fun retryChallengeCarriesCurrentResponseMarkers() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            when (engineCalls) {
                1 -> respond("first-only body", HttpStatusCode.Forbidden)
                2 -> respond("second-only body", HttpStatusCode.TooManyRequests)
                // Only a repeat challenge reaches the gate; a clean response
                // never consults the cooldown.
                3 -> respond("first-only body", HttpStatusCode.Forbidden)
                else -> respond("ok content")
            }
        }
        val solver = object : CfChallengeSolver {
            val requests = mutableListOf<CfChallenge>()

            override suspend fun solve(request: CfChallenge): CfSolution? {
                requests += request
                // Mirrors TwoPhaseCfSolver's bare-429 gate.
                if (request.status == 429 && request.markers.isEmpty() && !request.cfMitigated) return null
                return CfSolution(mapOf("cf_clearance" to "token-1"), null)
            }
        }
        val client = PluginHttpClient(
            metadata = metadata.copy(
                cfOptions = CfOptions(
                    markers = listOf("first-only"),
                    triggerMarkers = listOf("second-only"),
                ),
            ),
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.post("demo", "https://example.com/page", body = "q=x")
        }

        assertEquals(2, solver.requests.size)
        val retry = solver.requests[1]
        assertEquals(429, retry.status)
        assertTrue(retry.markers.isEmpty(), "stale first-response markers leaked: ${retry.markers}")
        assertEquals(false, retry.cfMitigated)
        // Bare-429 skip: no third request. The null solution then set the
        // failure cooldown, so the next challenge fast-fails without solving.
        assertEquals(2, engineCalls)
        assertFailsWith<CfChallengeRequiredException> {
            client.post("demo", "https://example.com/page", body = "q=x")
        }
        assertEquals(3, engineCalls)
        assertEquals(2, solver.requests.size)
    }

    @Test
    fun cfDecisionLineNamesVerdictAndTrigger() = runTest {
        val lines = RecordingLogger()
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond(
                    "<html><div id=\"challenge-running\"></div></html>",
                    HttpStatusCode.Forbidden,
                )
            } else {
                respond("ok")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = lines,
            engine = engine,
            solver = solver,
        )

        client.get("demo", "https://example.com/page")

        val cf = lines.lines.single { "event=cf verdict=challenge" in it }
        assertTrue("hidden=ran" in cf, cf)
        assertTrue("visible=no" in cf, cf)
        assertTrue("trigger=marker" in cf, cf)
        assertTrue("status=403" in cf, cf)
        assertTrue("url=https://example.com/page" in cf, cf)
    }

    @Test
    fun cfDecisionLineForPlainResponse() = runTest {
        val lines = RecordingLogger()
        val engine = MockEngine { respond("ok") }
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = lines,
            engine = engine,
        )

        client.get("demo", "https://example.com/page")

        val cf = lines.lines.single { "event=cf" in it }
        println("EXAMPLE-CF $cf")
        assertTrue("verdict=none" in cf, cf)
        assertTrue("hidden=skipped" in cf, cf)
        assertTrue("visible=no" in cf, cf)
        assertTrue("trigger=none" in cf, cf)
    }

    @Test
    fun challengeWithSolverRetriesOnceAndSucceeds() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/page")

        assertEquals(200, response.status.value)
        assertEquals("ok content", response.bodyAsText())
        assertEquals(2, engineCalls)
        assertEquals(1, solver.calls)
        assertEquals("example.com", solver.requests.single().host)
        assertTrue(cookieStore.cookies.any { it.name == "cf_clearance" && it.value == "token-1" })
    }

    @Test
    fun solverUserAgentAppliedOnRetry() = runTest {
        var engineCalls = 0
        var retryUserAgent: String? = null
        val engine = MockEngine { request ->
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                retryUserAgent = request.headers[io.ktor.http.HttpHeaders.UserAgent]
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), "SolverUA/7.7"))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/page")

        assertEquals(200, response.status.value)
        assertEquals("SolverUA/7.7", retryUserAgent)
    }

    @Test
    fun challengeCarriesEffectiveUserAgentAndRetryReusesIt() = runTest {
        val hostOptions = HostOptionsRegistry()
        hostOptions.setUserAgent("example.com", "owner", "HostUA/3.3")
        var engineCalls = 0
        var retryUserAgent: String? = null
        val engine = MockEngine { request ->
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                retryUserAgent = request.headers[HttpHeaders.UserAgent]
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
            hostOptions = hostOptions,
        )

        client.get("demo", "https://example.com/page")

        // The WebView must present the same UA the challenged request used...
        assertEquals("HostUA/3.3", solver.requests.single().userAgent)
        // ...and the retry (solution UA null) falls back to that same host UA.
        assertEquals("HostUA/3.3", retryUserAgent)
    }

    @Test
    fun callerUserAgentHeaderIsTheBoundChallengeUserAgent() = runTest {
        val engine = MockEngine {
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get(
                "demo",
                "https://example.com/page",
                headers = mapOf("user-agent" to "CallerUA/1.0"),
            )
        }

        assertEquals("CallerUA/1.0", solver.requests.first().userAgent)
    }

    // ScribbleHub admin-ajax (POST/XHR) has no page JS context: the solver must
    // open the Referer page, exactly like an /api/ challenge.
    @Test
    fun xhrChallengeSolverOpensRefererPage() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.post(
            "demo",
            "https://example.com/wp-admin/admin-ajax.php",
            body = "action=x",
            headers = mapOf("Referer" to "https://example.com/novel/one"),
        )

        val challenge = solver.requests.single()
        assertTrue(challenge.isXhr)
        assertEquals("https://example.com/wp-admin/admin-ajax.php", challenge.url)
        assertEquals("https://example.com/novel/one", challenge.navigateUrl)
    }

    // A form POST (69shuba search) is a browser NAVIGATION: CF serves it a real
    // page with a JS context, so the solver must open the exact request URL.
    // Only XHR/no-JS markers (X-Requested-With, Accept: json, /admin-ajax.php,
    // /api/) navigate to the Referer.
    @Test
    fun formPostChallengeSolverUsesRequestUrl() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.post(
            "demo",
            "https://example.com/search",
            body = "q=test",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to "https://example.com/",
            ),
        )

        val challenge = solver.requests.single()
        assertEquals("https://example.com/search", challenge.url)
        assertEquals("https://example.com/search", challenge.navigateUrl)
    }

    // Images opt out of the CF flow: a challenge is returned as-is. A
    // pre-seeded failure cooldown proves the gate was never consulted (it
    // would fast-fail with CfChallengeRequiredException), and the engine call
    // count proves there was no retry. The debug line still names the real
    // trigger instead of a hard-coded "none".
    @Test
    fun solveChallengesFalseReturnsChallengeAsIsWithoutGateOrSolver() = runTest {
        val gate = CfGate(failureCooldownMs = 60_000)
        gate.enterCooldown("example.com", success = false)
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("not an image", HttpStatusCode.Forbidden, headersOf(CF_MITIGATED, "challenge"))
        }
        val solver = FakeSolver(null)
        val lines = RecordingLogger()
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = lines,
            engine = engine,
            gate = gate,
            solver = solver,
        )

        val response = client.get(
            "demo",
            "https://example.com/cdn/image/123",
            solveChallenges = false,
        )

        assertEquals(403, response.status.value)
        assertEquals(1, engineCalls)
        assertEquals(0, solver.calls)
        val cf = lines.lines.single { "event=cf" in it }
        assertTrue("verdict=challenge" in cf, cf)
        assertTrue("trigger=cf-mitigated" in cf, cf)
    }

    // A cover URL is a static asset (NoveLA isNotCloudflare): even an explicit
    // cf-mitigated 403 is passed through, and asking for no solving must not
    // change that.
    @Test
    fun solveChallengesFalseLeavesStaticImageChallengeUntouched() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("not an image", HttpStatusCode.Forbidden, headersOf(CF_MITIGATED, "challenge"))
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get(
            "demo",
            "https://pic.example/cover.webp",
            solveChallenges = false,
        )

        assertEquals(403, response.status.value)
        assertEquals(1, engineCalls)
        assertEquals(0, solver.calls)
    }

    @Test
    fun challengeWithoutSolverFailsFast() = runTest {
        val engine = MockEngine {
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
        )

        val error = assertFailsWith<CfChallengeRequiredException> {
            client.get(
                "demo",
                "https://example.com/page",
                headers = mapOf("Referer" to "https://example.com/"),
            )
        }
        assertEquals("example.com", error.error.host)
        assertEquals("https://example.com/page", error.error.url)
        assertEquals("https://example.com/", error.error.referer)
        assertEquals(403, error.error.status)
        assertEquals(false, error.error.isXhr)
    }

    @Test
    fun solverFailureFailsFast() = runTest {
        val engine = MockEngine {
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val error = assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }
        assertEquals("example.com", error.error.host)
        assertEquals(1, solver.calls)
    }

    // NoveLA parity: MAX_MANUAL_ATTEMPTS=2 — a still-challenged retry buys ONE
    // more manual attempt (cookies cleared for the host, full re-bypass), and
    // the third challenge fails hard. The bound is what keeps this from looping.
    @Test
    fun retryStillChallengedDoesNotLoop() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }
        // 1 challenge + 2 solve+retry rounds, then hard fail — no recursion.
        assertEquals(3, engineCalls)
        assertEquals(2, solver.calls)
        // The second attempt started from a clean slate: the rejected clearance
        // was wiped from the store before re-solving (NoveLA
        // clearCookiesForDomain), so only the second solution's cookie remains.
        assertEquals(1, cookieStore.cookies.count { it.name == "cf_clearance" })
        assertEquals("token-1", cookieStore.cookies.single { it.name == "cf_clearance" }.value)
    }

    // NoveLA parity (CloudfareVerificationInterceptor.kt:302-306): a challenge
    // on an /api/ path is solved by opening the REFERER page in the browser —
    // a bare API URL has no JS context, and CF keeps re-challenging it even
    // with a fresh clearance (the fictionzone search bug).
    @Test
    fun apiChallengeSolverOpensRefererPage() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.get(
            "demo",
            "https://example.com/api/__api_party/demo",
            headers = mapOf("Referer" to "https://example.com/browse"),
        )

        val challenge = solver.requests.single()
        // The request URL stays the challenged API URL for markers/status...
        assertEquals("https://example.com/api/__api_party/demo", challenge.url)
        // ...but the solver opens the referer page.
        assertEquals("https://example.com/browse", challenge.referer)
        assertEquals("https://example.com/browse", challenge.navigateUrl)
    }

    // No caller Referer: the request carries the scheme://host/ default
    // (PluginRequestBuilder / NoveLA defaultHeaders) — the solver opens that.
    @Test
    fun apiChallengeWithoutCallerRefererOpensDefaultRoot() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.get("demo", "https://example.com/api/items")

        val challenge = solver.requests.single()
        assertEquals("https://example.com/", challenge.referer)
        assertEquals("https://example.com/", challenge.navigateUrl)
    }

    // A non-/api/ challenge keeps the request URL as the solver target even
    // when a referer is present — the page itself has a JS context.
    @Test
    fun pageChallengeKeepsRequestUrlAsSolverTarget() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.get(
            "demo",
            "https://example.com/novel/some-book",
            headers = mapOf("Referer" to "https://example.com/"),
        )

        val challenge = solver.requests.single()
        assertEquals("https://example.com/novel/some-book", challenge.navigateUrl)
    }

    // navigateUrl edge cases straight on the data class: blank/absent referer
    // falls back to the request URL.
    @Test
    fun navigateUrlFallsBackToRequestUrlWithoutUsableReferer() {
        assertEquals(
            "https://example.com/api/items",
            CfChallenge("example.com", "https://example.com/api/items", 403, emptyList())
                .navigateUrl,
        )
        assertEquals(
            "https://example.com/api/items",
            CfChallenge(
                "example.com", "https://example.com/api/items", 403, emptyList(), referer = " ",
            ).navigateUrl,
        )
    }

    // The solver target is plugin-derived: safeNavigateUrl keeps http(s)
    // targets (a private/LAN literal included — the WebView lane has its own
    // address guard) and fails closed (null) on any other scheme or userinfo.
    @Test
    fun safeNavigateUrlKeepsHttpTargetsAndRejectsOtherSchemes() {
        val challenge = CfChallenge(
            "example.com", "https://example.com/api/items", 403, emptyList(),
            referer = "https://example.com/browse",
            // An /api/ challenge is XHR by definition (no page JS context).
            isXhr = true,
        )
        assertEquals("https://example.com/browse", challenge.safeNavigateUrl())
        assertEquals(
            "http://192.168.1.10:8080/browse",
            challenge.copy(referer = "http://192.168.1.10:8080/browse").safeNavigateUrl(),
        )
        assertNull(challenge.copy(referer = "file:///etc/passwd").safeNavigateUrl())
        assertNull(challenge.copy(referer = "https://user:pass@example.com/").safeNavigateUrl())
    }

    @Test
    fun cooldownAfterFailedBypassFastFailsNextRequest() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }
        // Second challenge on the same host: gate fast-fails without calling solver.
        val fastFail = assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }
        // The cooldown path must carry the same request fields as the typed
        // challenge error so the caller can offer Solve for the retry.
        assertEquals("https://example.com/page", fastFail.error.url)
        assertEquals("https://example.com/", fastFail.error.referer)
        assertEquals(403, fastFail.error.status)
        assertEquals(1, solver.calls)
        assertEquals(2, engineCalls)
    }

    // NoveLA parity: a challenge arriving while cf_clearance is ATTACHED means
    // the clearance genuinely expired — re-solve even during the success
    // cooldown (the fictionzone bug: a re-challenge after the first solve
    // fast-failed for 5 minutes instead of re-solving).
    @Test
    fun rechallengeWithAttachedClearanceReSolves() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls % 2 == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val first = client.get("demo", "https://example.com/page")
        assertEquals(200, first.status.value)
        // cf_clearance=token-1 is now attached; the next challenge is a true
        // expiry and must re-solve despite the active success cooldown.
        val second = client.get("demo", "https://example.com/page")

        assertEquals(200, second.status.value)
        assertEquals(2, solver.calls)
        assertEquals(4, engineCalls)
    }

    // A challenge WITHOUT an attached cf_clearance during the success cooldown
    // is not an expiry: fast-fail (the cooldown's purpose — no solver loop).
    @Test
    fun rechallengeWithoutClearanceFastFailsDuringSuccessCooldown() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls % 2 == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("ok content")
            }
        }
        // Solution without cf_clearance: the store never gets one.
        val solver = FakeSolver(CfSolution(mapOf("__cf_bm" to "bm-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertEquals(200, client.get("demo", "https://example.com/page").status.value)
        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }

        assertEquals(1, solver.calls)
        assertEquals(3, engineCalls)
    }

    // A FAILED bypass's cooldown is absolute: even an attached cf_clearance
    // (possible heuristic false-positive from the challenge response's own
    // Set-Cookie) must not trigger another browser solve.
    @Test
    fun failureCooldownFastFailsEvenWithClearanceAttached() = runTest {
        cookieStore.setFromResponse(
            "https://example.com/",
            listOf("cf_clearance=stale; Path=/"),
        )
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }
        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }

        assertEquals(1, solver.calls)
        assertEquals(2, engineCalls)
    }

    // NoveLA clearCookiesForDomain parity: the re-solve path removes ONLY
    // cf_clearance — an unrelated cookie (sort order, locale) must survive
    // the second solve+retry round.
    @Test
    fun reSolveClearsOnlyCfClearance() = runTest {
        val store = FakeCookieStore()
        store.setFromResponse("https://example.com/", listOf("session=keep; Path=/"))
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
        }
        val solver = FakeSolver(CfSolution(mapOf("cf_clearance" to "token-1"), null))
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = store,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/page")
        }

        assertEquals(2, solver.calls)
        assertEquals(3, engineCalls)
        assertEquals("keep", store.get("https://example.com/").single { it.name == "session" }.value)
        assertEquals("token-1", store.get("https://example.com/").single { it.name == "cf_clearance" }.value)
    }

    // Parity with the reference client: a challenge body on a static-asset URL
    // is the origin's error page forwarded by a cover CDN — a bypass there is
    // pointless. The client passes response.request.url into the detector, so
    // no solver runs and the 403 passes through untouched.
    @Test
    fun staticAssetUrlWithChallengeBodyIsNotAChallenge() = runTest {
        val engine = MockEngine {
            respond(
                "<html>Just a moment...<div id=\"challenge-running\"></div></html>",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.Server, "cloudflare"),
            )
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://example.com/cover.png")

        assertEquals(403, response.status.value)
        assertEquals(0, solver.calls)
    }

    // NoveLA CLOUDFLARE_WHITELIST parity: known-good infrastructure never
    // enters the bypass flow, even when it forwards a challenge body as-is.
    @Test
    fun whitelistedHostNeverEntersBypass() = runTest {
        val engine = MockEngine {
            respond(
                "<html>Just a moment...<div id=\"challenge-running\"></div></html>",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.Server, "cloudflare"),
            )
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        val response = client.get("demo", "https://raw.githubusercontent.com/org/repo/file.lua")

        assertEquals(403, response.status.value)
        assertEquals(0, solver.calls)
    }

    // NoveLA IP_BLOCKED_URL_MARKERS parity: the edge answered with a hard IP
    // ban (/cdn-cgi/error/) — no bypass will ever clear it, so the failure
    // path (fast-fail, no solver) runs instead of a silent success. A plain
    // 200 with no markers proves the URL alone drives the verdict.
    @Test
    fun ipBlockedUrlIsDetectedAsChallenge() = runTest {
        val engine = MockEngine {
            respond("Error 1020", HttpStatusCode.OK)
        }
        val solver = FakeSolver(null)
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        assertFailsWith<CfChallengeRequiredException> {
            client.get("demo", "https://example.com/cdn-cgi/error/?error=1020")
        }
        // One solve attempt, then the terminal failure path — never a retry loop.
        assertEquals(1, solver.calls)
    }
}
