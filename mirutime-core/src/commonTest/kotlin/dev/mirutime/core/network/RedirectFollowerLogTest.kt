package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.core.network.security.SsrfGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// FWN `search-adv` 301 diagnostics: raw Location AND resolved target, hop
// number, query presence (never the value), and whether the resolved target is
// byte-identical to the source. A Location that drops the query must resolve to
// a DIFFERENT URL (`same=false, nextQuery=absent`), not re-request itself.
class RedirectFollowerLogTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://freewebnovel.com",
        contentType = ContentType.NOVEL,
        language = "en",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun client(engine: MockEngine, logger: RecordingLogger, guard: SsrfGuard? = null) =
        PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = logger,
            engine = engine,
            ssrfGuard = guard,
        )

    @Test
    fun rawAndResolvedLocationAreBothLogged() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    "",
                    HttpStatusCode.MovedPermanently,
                    headersOf(HttpHeaders.Location, "localhost/search-adv"),
                )
            } else {
                respond("ok")
            }
        }

        client(engine, logger).get(
            "demo",
            "https://freewebnovel.com/search-adv?genre_match=all&keyword=secret",
        )

        val line = logger.lines.single { "event=redirect" in it }
        assertTrue("hop=1" in line, line)
        assertTrue("status=301" in line, line)
        assertTrue("from=https://freewebnovel.com/search-adv" in line, line)
        assertTrue("raw=localhost/search-adv" in line, line)
        assertTrue("rawQuery=absent" in line, line)
        assertTrue("next=https://freewebnovel.com/localhost/search-adv" in line, line)
        // RFC 3986: a reference without a query part has NO query — the base
        // query must not be inherited (that inheritance was the self-loop).
        assertTrue("nextQuery=absent" in line, line)
        assertTrue("same=false" in line, line)
        assertTrue("blocked=none" in line, line)
        assertFalse("keyword" in line, "query values must never be logged: $line")
        assertFalse("?genre_match" in line, line)

        val net = logger.lines.single { "event=net method=GET" in it }
        assertTrue("redirects=1" in net, net)
        assertTrue("finalUrl=https://freewebnovel.com/localhost/search-adv" in net, net)
        assertTrue("pluginSuccess=true" in net, net)
        assertTrue("elapsed=" in net, net)
    }

    @Test
    fun queryDroppingLocationDropsBaseQuery() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    "",
                    HttpStatusCode.MovedPermanently,
                    headersOf(HttpHeaders.Location, "/search-adv"),
                )
            } else {
                respond("ok")
            }
        }

        val response = client(engine, logger).get("demo", "https://freewebnovel.com/search-adv?q=secret")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
        val line = logger.lines.single { "event=redirect" in it && "hop=1" in it }
        assertTrue("raw=/search-adv" in line, line)
        assertTrue("rawQuery=absent" in line, line)
        assertTrue("next=https://freewebnovel.com/search-adv" in line, line)
        assertTrue("nextQuery=absent" in line, line)
        assertTrue("same=false" in line, line)
        assertFalse("secret" in line, line)
    }

    @Test
    fun fragmentOnlyLocationIsNotReRequested() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "",
                HttpStatusCode.MovedPermanently,
                headersOf(HttpHeaders.Location, "#section"),
            )
        }

        val response = client(engine, logger).get("demo", "https://freewebnovel.com/a?q=secret")

        assertEquals(HttpStatusCode.MovedPermanently, response.status)
        assertEquals(1, calls)
        val line = logger.lines.single { "event=redirect" in it }
        assertTrue("same=true" in line, line)
        assertTrue("blocked=none" in line, line)
        assertFalse("loop=true" in line, line)
        assertFalse("secret" in line, line)
    }

    @Test
    fun ssrfTargetIsLoggedAsBlocked() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "http://127.0.0.1/secret?token=x"),
            )
        }

        assertFailsWith<SsrfBlockedException> {
            client(engine, logger, SsrfGuard()).get("demo", "https://freewebnovel.com/search-adv")
        }

        val lines = logger.lines.filter { "event=redirect" in it }
        assertTrue(lines.any { "blocked=ssrf" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "next=http://127.0.0.1/secret" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "rawQuery=present" in it }, lines.joinToString("\n"))
        assertTrue(lines.any { "nextQuery=present" in it }, lines.joinToString("\n"))
        assertFalse(lines.any { "token" in it }, lines.joinToString("\n"))
    }

    @Test
    fun maxRedirectsIsLoggedWithHopNumbers() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "/r$calls?q=secret"),
            )
        }

        val response = assertFailsWith<RedirectLimitException> {
            client(engine, logger).get("demo", "https://freewebnovel.com/start")
        }

        assertEquals("https://freewebnovel.com/r20?q=secret", response.url)
        assertEquals(21, calls)
        val redirects = logger.lines.filter { "event=redirect" in it }
        assertTrue(redirects.any { "hop=20" in it }, redirects.joinToString("\n"))
        assertTrue(redirects.any { "hop=21" in it && "blocked=max" in it }, redirects.joinToString("\n"))
        assertTrue(redirects.none { "secret" in it }, redirects.joinToString("\n"))
    }

    @Test
    fun repeatedTargetIsLoggedAsLoop() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "/loop"),
            )
        }

        // A permanent loop ends as a typed overflow, not as a 3xx document.
        assertFailsWith<RedirectLimitException> {
            client(engine, logger).get("demo", "https://freewebnovel.com/loop")
        }

        assertTrue(
            logger.lines.any { "event=redirect" in it && "loop=true" in it && "same=true" in it },
            logger.lines.joinToString("\n"),
        )
    }

    @Test
    fun exampleRedirectLine() {
        val line = formatRedirectTrace(
            status = 301,
            from = "https://freewebnovel.com/search-adv?genre_match=all",
            rawLocation = "/search-adv",
            nextUrl = "https://freewebnovel.com/search-adv?genre_match=all",
            blocked = "none",
            loop = true,
            hop = 3,
        )
        println("EXAMPLE-REDIRECT $line")
        assertTrue("hop=3" in line, line)
        assertTrue("same=true" in line, line)
    }

    @Test
    fun formatterNeverCarriesTheQuery() {
        val line = formatRedirectTrace(
            status = 301,
            from = "https://freewebnovel.com/search-adv?genre_match=all&keyword=secret",
            rawLocation = "localhost/search-adv?keyword=secret",
            nextUrl = "https://freewebnovel.com/localhost/search-adv?keyword=secret",
            blocked = "none",
            loop = false,
            hop = 2,
        )

        assertTrue(
            "hop=2 status=301 from=https://freewebnovel.com/search-adv " +
                "raw=localhost/search-adv rawQuery=present " +
                "next=https://freewebnovel.com/localhost/search-adv nextQuery=present " +
                "same=false protection=none blocked=none" in line,
            line,
        )
        assertFalse("keyword" in line, line)
        assertFalse("?" in line, line)
    }

    @Test
    fun schemeChangeIsNotSameEvenWithIdenticalQuery() {
        val line = formatRedirectTrace(
            status = 301,
            from = "http://host.example/path?q=secret",
            rawLocation = "https://host.example/path?q=secret",
            nextUrl = "https://host.example/path?q=secret",
            blocked = "none",
            loop = false,
        )

        assertTrue("same=false" in line, line)
        assertTrue("rawQuery=present" in line, line)
        assertTrue("nextQuery=present" in line, line)
        assertFalse("secret" in line, line)
    }

    @Test
    fun identicalTargetIncludingQueryIsSame() {
        val line = formatRedirectTrace(
            status = 301,
            from = "https://host.example/path?q=secret",
            rawLocation = "/path?q=secret",
            nextUrl = "https://host.example/path?q=secret",
            blocked = "none",
            loop = false,
        )

        assertTrue("same=true" in line, line)
        assertFalse("secret" in line, line)
    }
}
