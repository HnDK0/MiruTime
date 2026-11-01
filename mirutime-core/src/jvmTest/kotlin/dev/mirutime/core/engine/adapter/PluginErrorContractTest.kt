package dev.mirutime.core.engine.adapter

import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

// `show_error` is the SEMANTIC plugin contract (auth/adult/paid): when the
// plugin calls it, that UserMessage wins over the host's transport-failure
// flag. Transport errors without a plugin message come from the flag instead.
class PluginErrorContractTest {

    private fun metadata() = PluginMetadata(
        id = "dev.mirutime.contract",
        name = "Contract",
        baseUrl = "https://site.example",
        contentType = dev.mirutime.core.api.ContentType.NOVEL,
        language = "en",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun source(engine: MockEngine, script: String): PluginSource =
        LuaPluginSourceFactory(
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            keyValueStore = FakeKeyValueStore(),
            logger = RecordingLogger(),
            engine = engine,
            fileSystem = FakeFileSystem(),
            hostOptions = null,
            ssrfGuard = null,
        ).create(PluginDescriptor(metadata(), script))

    @Test
    fun `show_error after a failed fetch surfaces the plugin message`() = runBlocking {
        val engine = MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) }
        val source = source(
            engine,
            """
            function getCatalogSearch(index, query)
              local r = http_get("https://site.example/search")
              if not r.success then
                show_error("Source unavailable", "HTTP 503", nil)
                return { items = {}, hasNext = false }
              end
              return { items = {}, hasNext = false }
            end
            """.trimIndent(),
        )

        val result = source.search(0, "query")

        val failure = assertIs<PluginError.UserMessage>(
            (result as Outcome.Err).error,
            "show_error must not be replaced by the transport flag",
        )
        assertEquals("Source unavailable", failure.title)
        assertEquals("HTTP 503", failure.message)
    }

    @Test
    fun `successful empty search is not an error`() = runBlocking {
        val source = source(
            MockEngine { respond("ok") },
            """
            function getCatalogSearch(index, query)
              return { items = {}, hasNext = false }
            end
            """.trimIndent(),
        )

        val result = source.search(0, "query")

        assertEquals(emptyList(), assertIs<Outcome.Ok<CatalogPage>>(result).value.items)
    }

    @Test
    fun `transport failure without show_error becomes typed Network`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/search") {
                respond("down", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok")
            }
        }
        val source = source(
            engine,
            """
            function getCatalogSearch(index, query)
              local r = http_get("https://site.example/search")
              return { items = {}, hasNext = false }
            end
            """.trimIndent(),
        )

        val failure = assertIs<PluginError.Network>((source.search(0, "query") as Outcome.Err).error)
        assertEquals(503, failure.status)
        assertEquals("site.example", failure.host)
        assertEquals("/search", failure.path)
        assertEquals(null, source.search(0, "other").getOrNull())
    }
}
