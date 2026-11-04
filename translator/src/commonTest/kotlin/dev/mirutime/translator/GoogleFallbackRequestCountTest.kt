package dev.mirutime.translator

import dev.mirutime.translator.internal.KtorTranslationHttpClient
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.provider.GooglePaProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * A merged chunk recovers by bisection, not by retrying the same deterministic
 * body: the executor's retry ladder must not multiply the request count.
 */
class GoogleFallbackRequestCountTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `merged pa chunk costs one bisection, not maxAttempts per node`() = networkTest {
        var posts = 0
        val engine = MockEngine { request ->
            posts++
            val payload = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonArray
            val text = payload[0].jsonArray[0].jsonPrimitive.content
            // A multi-unit chunk comes back as one block: the CJK failure the
            // bisection exists for.
            val block = if (text.contains("</p><p>")) "<p>merged</p>" else "<p>$text</p>"
            respond(
                content = buildJsonArray {
                    add(buildJsonArray { add(JsonPrimitive(block)) })
                }.toString(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = GooglePaProvider(
            http = KtorTranslationHttpClient(HttpClient(engine)),
            config = TranslationProviderConfig(
                provider = ProviderId.GOOGLE_PA,
                apiKeys = listOf("key"),
                baseUrl = "https://test/pa",
                maxAttempts = 6,
                chunkConcurrency = 2,
            ),
            timeSource = TimeSource.Monotonic,
        )

        val result = provider.translate(
            ProviderRequest(
                units = listOf(TranslationUnit("a", "one"), TranslationUnit("b", "two")),
                source = null,
                target = LanguageCode.parse("es"),
                prompt = null,
            ),
        )

        assertEquals(mapOf("a" to "one", "b" to "two"), result.translations)
        // Root + one child per unit. Retrying the deterministic truncation
        // before bisecting would make this 6 + 2 = 8.
        assertEquals(3, posts)
    }

    private fun networkTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }
}
