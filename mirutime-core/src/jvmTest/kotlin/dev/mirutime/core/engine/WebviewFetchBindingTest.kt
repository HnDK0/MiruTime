package dev.mirutime.core.engine

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.engine.binding.NetworkBindings
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.host.WebviewFetcher
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.security.SsrfBlockedException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

// `webview_fetch` contract: installed only when the host wires a fetcher, one
// fetch call per Lua call, and every failure (SSRF, exception, timeout) is a
// plain nil so dood keeps its existing "no pass_md5" fallback.
class WebviewFetchBindingTest {

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

    private fun client(engine: MockEngine): PluginHttpClient = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    private fun session(
        script: String,
        fetcher: WebviewFetcher?,
        defaultTimeoutMs: Long = NetworkBindings.DEFAULT_TIMEOUT_MS,
    ): LuaSession {
        val s = createLuaSession("dev.mirutime.demo", script)
        NetworkBindings(
            client(MockEngine { respond("unused") }),
            "dev.mirutime.demo",
            defaultTimeoutMs = defaultTimeoutMs,
            webviewFetcher = fetcher,
        ).install(s)
        return s
    }

    @Test
    fun absentWithoutFetcher() {
        val s = session(
            """
            function check()
                return type(webview_fetch)
            end
            """.trimIndent(),
            fetcher = null,
        )

        val result = s.call("check", emptyList())
        s.dispose()

        assertEquals(LuaData.LuaStr("nil"), result.data)
    }

    @Test
    fun wiredFetchIsCalledOnceAndReturnsHtml() {
        val calls = AtomicInteger()
        var seenUrl: String? = null
        var seenClick: String? = null
        var seenExpect: String? = null
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? {
                calls.incrementAndGet()
                seenUrl = url
                seenClick = click
                seenExpect = expect
                return "<html>ok</html>"
            }
        }
        val s = session(
            """
            function get()
                return webview_fetch("https://example.com/e/abc", {
                    click = "$('a').click()",
                    expect = "/pass_md5/",
                })
            end
            """.trimIndent(),
            fetcher,
        )

        val result = s.call("get", emptyList())
        // Exactly one hidden-WebView fetch per Lua call.
        assertEquals(1, calls.get(), "one fetch per Lua call")
        val second = s.call("get", emptyList())
        s.dispose()

        assertEquals(LuaData.LuaStr("<html>ok</html>"), result.data)
        assertEquals(LuaData.LuaStr("<html>ok</html>"), second.data)
        assertEquals(2, calls.get(), "one fetch per Lua call")
        assertEquals("https://example.com/e/abc", seenUrl)
        assertEquals("a", seenClick, "the jQuery form is unwrapped before the host")
        assertEquals("/pass_md5/", seenExpect)
    }

    @Test
    fun invalidClickReturnsNilWithoutFetch() {
        val calls = AtomicInteger()
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? {
                calls.incrementAndGet()
                return "<html>ok</html>"
            }
        }
        val s = session(
            """
            function get()
                return webview_fetch("https://example.com/e/abc", {
                    click = "alert(1)",
                })
            end
            """.trimIndent(),
            fetcher,
        )

        val result = s.call("get", emptyList())
        s.dispose()

        assertNull(result.error)
        assertEquals(LuaData.LuaNil, result.data)
        assertEquals(0, calls.get(), "an invalid click must not open a WebView")
    }

    @Test
    fun nonStringClickReturnsNilWithoutFetch() {
        val calls = AtomicInteger()
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? {
                calls.incrementAndGet()
                return "<html>ok</html>"
            }
        }
        val s = session(
            """
            function get()
                return webview_fetch("https://example.com/e/abc", { click = 42 })
            end
            """.trimIndent(),
            fetcher,
        )

        val result = s.call("get", emptyList())
        s.dispose()

        assertEquals(LuaData.LuaNil, result.data)
        assertEquals(0, calls.get())
    }

    @Test
    fun ssrfBlockedReturnsNil() {
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? = throw SsrfBlockedException("loopback")
        }
        val s = session(
            """
            function get()
                return webview_fetch("https://127.0.0.1/e/1")
            end
            """.trimIndent(),
            fetcher,
        )

        val result = s.call("get", emptyList())
        s.dispose()

        assertNull(result.error)
        assertEquals(LuaData.LuaNil, result.data)
    }

    @Test
    fun exceptionReturnsNil() {
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? = throw IllegalStateException("webview exploded")
        }
        val s = session(
            """
            function get()
                return webview_fetch("https://example.com/e/1")
            end
            """.trimIndent(),
            fetcher,
        )

        val result = s.call("get", emptyList())
        s.dispose()

        assertNull(result.error)
        assertEquals(LuaData.LuaNil, result.data)
    }

    @Test
    fun timeoutReturnsNilAndReleasesTheWorker() {
        val fetcher = object : WebviewFetcher {
            override suspend fun fetch(
                url: String,
                click: String?,
                expect: String?,
                timeoutMs: Long,
            ): String? {
                delay(5_000)
                return "<html>late</html>"
            }
        }
        val s = session(
            """
            function slow() return webview_fetch("https://example.com/e/slow") end
            function fast() return type(webview_fetch) end
            """.trimIndent(),
            fetcher,
            defaultTimeoutMs = 100,
        )

        var data: LuaData? = null
        val elapsed = measureTimeMillis { data = s.call("slow", emptyList()).data }
        val next = s.call("fast", emptyList()).data
        s.dispose()

        assertEquals(LuaData.LuaNil, data)
        assertTrue(elapsed < 2_000, "webview_fetch leaked past its timeout: ${elapsed}ms")
        assertEquals(LuaData.LuaStr("function"), next)
    }
}
