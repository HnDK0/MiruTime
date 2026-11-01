package dev.mirutime.translator

import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** Guarantees the metadata path only ever uses free providers. */
class MetadataFallbackTest {

    @Test
    fun `free chain starts with google pa`() = networkTest {
        val manager = managerFor(paStatus = HttpStatusCode.OK, freeStatus = HttpStatusCode.OK)

        val result = manager.translateMetadata(metadata(ProviderId.GEMINI)).requireOk()

        assertEquals("Hola", result.translations["0"])
        assertEquals(ProviderId.GOOGLE_PA, result.provider)
    }

    @Test
    fun `chain falls through to google free when pa fails`() = networkTest {
        val manager = managerFor(
            paStatus = HttpStatusCode.InternalServerError,
            freeStatus = HttpStatusCode.OK,
        )

        val result = manager.translateMetadata(metadata(ProviderId.OPENAI)).requireOk()

        assertEquals("Hola", result.translations["0"])
        assertEquals(ProviderId.GOOGLE_FREE, result.provider)
    }

    @Test
    fun `explicit free choice is honored without a chain`() = networkTest {
        val manager = managerFor(paStatus = HttpStatusCode.OK, freeStatus = HttpStatusCode.OK)

        val result = manager.translateMetadata(metadata(ProviderId.GOOGLE_FREE)).requireOk()

        assertEquals(ProviderId.GOOGLE_FREE, result.provider)
    }

    @Test
    fun `all free providers failing returns an error`() = networkTest {
        val manager = managerFor(
            paStatus = HttpStatusCode.InternalServerError,
            freeStatus = HttpStatusCode.InternalServerError,
        )

        manager.translateMetadata(metadata(ProviderId.DEEPL)).requireErr()
    }

    // Ktor + MockEngine need a real dispatcher; runTest's virtual time trips the provider timeout.
    private fun networkTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun managerFor(
        paStatus: HttpStatusCode,
        freeStatus: HttpStatusCode,
    ): TranslationManagerImpl = TranslationManagerImpl(
        client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.contains("pa")) {
                    respond(
                        content = """[["Hola"]]""",
                        status = paStatus,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """[[["Hola", "Hello", null, null]], null, "en"]""",
                        status = freeStatus,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ),
        configs = listOf(
            TranslationProviderConfig(
                provider = ProviderId.GOOGLE_PA,
                apiKeys = listOf("test-key"),
                baseUrl = "https://test/pa",
                maxAttempts = 1,
            ),
            TranslationProviderConfig(
                provider = ProviderId.GOOGLE_FREE,
                apiKeys = listOf("test-key"),
                baseUrl = "https://test/free",
                maxAttempts = 1,
            ),
        ),
    )

    private fun metadata(provider: ProviderId): MetadataTranslationRequest =
        MetadataTranslationRequest(
            bookId = 1,
            key = "meta:book",
            units = listOf(TranslationUnit("0", "Hello")),
            source = LanguageCode.parse("en"),
            target = LanguageCode.parse("es"),
            provider = provider,
        )

    private fun <T> Outcome<T>.requireOk(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> fail("expected Ok, got $error")
    }

    private fun <T> Outcome<T>.requireErr(): MiruError = when (this) {
        is Outcome.Ok -> fail("expected Err, got $value")
        is Outcome.Err -> error
    }
}