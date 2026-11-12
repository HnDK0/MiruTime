package dev.mirutime.translator

import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.internal.parseTranslations
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TranslationManagerTest {
    @Test
    fun googlePaTranslatesBatch() = networkTest {
        val manager = managerFor(
            body = """[["Hola"]]""",
            config = config(ProviderId.GOOGLE_PA, "https://test/pa"),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(ProviderId.GOOGLE_PA, result.provider)
        assertEquals(null, result.model)
    }

    @Test
    fun googleFreeTranslatesBatch() = networkTest {
        val manager = managerFor(
            body = """[[["Hola", "Hello", null, null]], null, "en"]""",
            config = config(ProviderId.GOOGLE_FREE, "https://test/free"),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_FREE, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
    }

    @Test
    fun geminiParsesStructuredJson() = networkTest {
        val inner = """{"translations":[{"id":"1","text":"Hola"}]}"""
        val body = """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(inner)}}]},"finishReason":"STOP"}]}"""
        val manager = managerFor(
            body = body,
            config = config(ProviderId.GEMINI, "https://test/gemini"),
        )

        val result = manager.translateBatch(request(ProviderId.GEMINI, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals("gemini-2.5-flash-lite", result.model)
    }

    @Test
    fun openAiParsesOutputText() = networkTest {
        val inner = """{"translations":[{"id":"1","text":"Hola"}]}"""
        val body = """{"output_text":${JsonPrimitive(inner)}}"""
        val manager = managerFor(
            body = body,
            config = config(ProviderId.OPENAI, "https://test/openai"),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals("gpt-4o-mini", result.model)
    }

    @Test
    fun managerDetectsSourceBeforeTranslation() = networkTest {
        val engine = MockEngine { request ->
            val isDetection = request.url.parameters["tl"] == "en"
            respond(
                content = if (isDetection) {
                    """[null, null, "en"]"""
                } else {
                    """[[["Hola", "Hello", null, null]], null, "en"]"""
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_FREE, "https://test/free")),
        )

        val result = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(LanguageCode.parse("en"), result.detectedSource)
    }

    @Test
    fun managerDetectsSourceSkipsPunctuationFirstUnit() = networkTest {
        var detectedQuery: String? = null
        val engine = MockEngine { request ->
            val isDetection = request.url.parameters["tl"] == "en"
            if (isDetection) {
                detectedQuery = request.url.parameters["q"]
            }
            respond(
                content = if (isDetection) {
                    """[null, null, "en"]"""
                } else {
                    """[[["***\n", "***\n", null, null],["Hola", "Hello", null, null]], null, "en"]"""
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_FREE, "https://test/free")),
        )

        val result = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "***", "Hello", source = null),
        ).requireOk()

        assertEquals("Hello", detectedQuery)
        assertEquals(LanguageCode.parse("en"), result.detectedSource)
    }

    @Test
    fun rateLimitedIsTyped() = networkTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "0"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_PA, "https://test/pa", maxAttempts = 1)),
        )

        val error = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireErr()

        assertIs<TranslationError.RateLimited>(error)
        assertEquals(ProviderId.GOOGLE_PA, error.provider)
    }

    @Test
    fun geminiMaxTokensIsTruncated() = networkTest {
        val body = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}"""
        val manager = managerFor(
            body = body,
            config = config(ProviderId.GEMINI, "https://test/gemini"),
        )

        val error = manager.translateBatch(request(ProviderId.GEMINI, "Hello")).requireErr()

        assertIs<TranslationError.Truncated>(error)
    }

    @Test
    fun quotaIsNotMaskedAsAuth() = networkTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"type":"insufficient_quota"}}""",
                status = HttpStatusCode.TooManyRequests,
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.OPENAI,
                    apiKeys = listOf("one", "two"),
                    baseUrl = "https://test/openai",
                    maxAttempts = 2,
                ),
            ),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.Quota>(error)
    }

    @Test
    fun geminiTruncatedResponseIsRetried() = networkTest {
        val inner = """{"translations":[{"id":"1","text":"Hola"}]}"""
        val success = """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(inner)}}]},"finishReason":"STOP"}]}"""
        val truncated = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}"""
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                content = if (calls == 1) truncated else success,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                config(ProviderId.GEMINI, "https://test/gemini", maxAttempts = 2),
            ),
        )

        val result = manager.translateBatch(request(ProviderId.GEMINI, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(2, calls)
    }

    @Test
    fun titleFallsBackToGooglePa() = networkTest {
        val manager = managerFor(
            body = """[["Hola"]]""",
            config = config(ProviderId.GOOGLE_PA, "https://test/pa"),
        )

        val result = manager.translateTitle(
            TitleTranslationRequest(
                bookId = 1,
                title = "Hello",
                source = LanguageCode.parse("en"),
                target = LanguageCode.parse("es"),
                provider = ProviderId.GEMINI,
            ),
        ).requireOk()

        assertEquals("Hola", result)
    }

    @Test
    fun authFailureRotatesToNextKey() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = """[["Hola"]]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.GOOGLE_PA,
                    apiKeys = listOf("bad", "good"),
                    baseUrl = "https://test/pa",
                    maxAttempts = 2,
                ),
            ),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(2, calls)
    }

    @Test
    fun paMalformedResponseIsInvalidResponse() = networkTest {
        val manager = managerFor(
            body = "not-json",
            config = config(ProviderId.GOOGLE_PA, "https://test/pa"),
        )

        val error = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireErr()

        assertIs<TranslationError.InvalidResponse>(error)
    }

    @Test
    fun blankUnitsDoNotRequireProvider() = networkTest {
        val manager = TranslationManagerImpl(
            client = HttpClient(MockEngine { error("must not be called") }),
            configs = emptyList(),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "   ")).requireOk()

        assertEquals("   ", result.translations["1"])
    }

    // LLM providers must send the real TranslationUnit ids.
    @Test
    fun geminiUsesActualUnitIds() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        val seen = mutableListOf<String>()
        val engine = translatingEngine(ProviderId.GEMINI) { seen += idsIn(it) }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GEMINI, "https://test/gemini")),
        )

        val result = manager.translateBatch(request(ProviderId.GEMINI, units)).requireOk()

        assertEquals(listOf("a", "b"), seen)
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
    }

    @Test
    fun openAiUsesActualUnitIds() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        val seen = mutableListOf<String>()
        val engine = translatingEngine(ProviderId.OPENAI) { seen += idsIn(it) }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.OPENAI, "https://test/openai")),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI, units)).requireOk()

        assertEquals(listOf("a", "b"), seen)
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
    }

    @Test
    fun largeBatchKeepsActualIdsAcrossGroups() = networkTest {
        val units = (0 until 61).map { TranslationUnit("unit-$it", "text-$it") }
        for (provider in listOf(ProviderId.GEMINI, ProviderId.OPENAI)) {
            val seen = mutableListOf<String>()
            val engine = translatingEngine(provider) { seen += idsIn(it) }
            val manager = TranslationManagerImpl(
                client = HttpClient(engine),
                configs = listOf(
                    config(provider, "https://test/$provider", maxCharsPerRequest = 20),
                ),
            )

            val result = manager.translateBatch(request(provider, units)).requireOk()

            assertEquals(units.map { it.id }, seen)
            assertEquals(61, result.translations.size)
            assertEquals("T:text-60", result.translations["unit-60"])
        }
    }

    @Test
    fun llmSendsWholeChapterInOneRequestWithoutUnitCap() = networkTest {
        val units = (0 until 61).map { TranslationUnit("unit-$it", "text-$it") }
        for (provider in listOf(ProviderId.GEMINI, ProviderId.OPENAI)) {
            var calls = 0
            val engine = translatingEngine(provider) { calls++ }
            val manager = TranslationManagerImpl(
                client = HttpClient(engine),
                configs = listOf(
                    config(provider, "https://test/$provider", maxCharsPerRequest = 10_000),
                ),
            )

            val result = manager.translateBatch(request(provider, units)).requireOk()

            assertEquals(1, calls)
            assertEquals(61, result.translations.size)
            assertEquals("T:text-60", result.translations["unit-60"])
        }
    }

    // Key cooldown must not eat retry attempts.
    @Test
    fun singleKeyNetworkErrorRetries() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) throw IllegalStateException("network down")
            respond(
                content = """[["Hola"]]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.GOOGLE_PA,
                    apiKeys = listOf("only"),
                    baseUrl = "https://test/pa",
                    maxAttempts = 2,
                ),
            ),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(2, calls)
    }

    @Test
    fun singleKeyRateLimitWithoutRetryAfterRetries() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(content = "", status = HttpStatusCode.TooManyRequests)
            } else {
                respond(
                    content = """[["Hola"]]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.GOOGLE_PA,
                    apiKeys = listOf("only"),
                    baseUrl = "https://test/pa",
                    maxAttempts = 2,
                ),
            ),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(2, calls)
    }

    @Test
    fun keylessGoogleFreeRateLimitRetries() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(content = "", status = HttpStatusCode.TooManyRequests)
            } else {
                respond(
                    content = """[[["Hola", "Hello", null, null]], null, "en"]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_FREE, "https://test/free", maxAttempts = 2)),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_FREE, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(2, calls)
    }

    @Test
    fun multiKeyRateLimitWithRetryAfterRotatesImmediately() = networkTest {
        val usedKeys = mutableListOf<String>()
        val engine = MockEngine { request ->
            usedKeys += request.headers["X-Goog-Api-Key"].orEmpty()
            if (usedKeys.size == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "120"),
                )
            } else {
                respond(
                    content = """[["Hola"]]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.GOOGLE_PA,
                    apiKeys = listOf("key-one", "key-two"),
                    baseUrl = "https://test/pa",
                    maxAttempts = 2,
                ),
            ),
        )

        val started = TimeSource.Monotonic.markNow()
        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertTrue(started.elapsedNow() < 10.seconds, "rotation must not sleep before the next key")
        assertEquals(listOf("key-one", "key-two"), usedKeys)
        assertEquals("Hola", result.translations["1"])
    }

    @Test
    fun rateLimitedKeepsFullRetryAfter() = networkTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "45"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_PA, "https://test/pa", maxAttempts = 1)),
        )

        val error = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireErr()

        assertIs<TranslationError.RateLimited>(error)
        assertEquals(45.seconds, error.retryAfter)
    }

    // Detection failures are typed; only InvalidResponse may be skipped.
    @Test
    fun googleFreeDetectionRateLimitIsTyped() = networkTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.TooManyRequests)
        }
        val manager = detectionManager(engine)

        val error = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireErr()

        assertIs<TranslationError.RateLimited>(error)
    }

    @Test
    fun googleFreeDetectionQuotaIsTyped() = networkTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"quota exceeded"}""",
                status = HttpStatusCode.TooManyRequests,
            )
        }
        val manager = detectionManager(engine)

        val error = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireErr()

        assertIs<TranslationError.Quota>(error)
    }

    @Test
    fun googleFreeDetectionServerErrorIsTyped() = networkTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.ServiceUnavailable)
        }
        val manager = detectionManager(engine)

        val error = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireErr()

        assertIs<TranslationError.Server>(error)
        assertEquals(503, error.status)
    }

    @Test
    fun googleFreeDetectionNetworkErrorIsTyped() = networkTest {
        val engine = MockEngine { throw IllegalStateException("offline") }
        val manager = detectionManager(engine)

        val error = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireErr()

        assertIs<TranslationError.Network>(error)
    }

    @Test
    fun invalidDetectionResponseIsSkipped() = networkTest {
        val engine = MockEngine { request ->
            if (request.url.parameters["tl"] == "en") {
                respond(
                    content = "not-json",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = """[[["Hola", "Hello", null, null]], null, "en"]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val manager = detectionManager(engine)

        val result = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, "Hello", source = null),
        ).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(null, result.detectedSource)
    }

    // Titles fall back strictly PA -> Free and reject no-op translations.
    @Test
    fun titleDoesNotFallBackToLlm() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GEMINI, "https://test/gemini")),
        )

        val error = manager.translateTitle(
            TitleTranslationRequest(
                bookId = 1,
                title = "Hello",
                source = LanguageCode.parse("en"),
                target = LanguageCode.parse("es"),
                provider = ProviderId.GEMINI,
            ),
        ).requireErr()

        assertEquals(0, calls)
        assertIs<TranslationError.InvalidRequest>(error)
    }

    @Test
    fun titleRejectsUnchangedTranslation() = networkTest {
        val manager = managerFor(
            body = """[["Hello"]]""",
            config = config(ProviderId.GOOGLE_PA, "https://test/pa"),
        )

        val error = manager.translateTitle(
            TitleTranslationRequest(
                bookId = 1,
                title = "Hello",
                source = LanguageCode.parse("en"),
                target = LanguageCode.parse("es"),
                provider = ProviderId.GOOGLE_PA,
            ),
        ).requireErr()

        assertIs<TranslationError.InvalidResponse>(error)
    }

    @Test
    fun blankTitleIsInvalidRequest() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = """[["Hola"]]""", status = HttpStatusCode.OK)
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_PA, "https://test/pa")),
        )

        val error = manager.translateTitle(
            TitleTranslationRequest(
                bookId = 1,
                title = "   ",
                source = LanguageCode.parse("en"),
                target = LanguageCode.parse("es"),
                provider = ProviderId.GOOGLE_PA,
            ),
        ).requireErr()

        assertEquals(0, calls)
        assertIs<TranslationError.InvalidRequest>(error)
    }

    // Plain-text fallback keeps wrapped continuation lines.
    @Test
    fun numberedFallbackKeepsContinuationLines() {
        val units = listOf(
            TranslationUnit("a", "Hello world"),
            TranslationUnit("b", "Goodbye"),
        )

        val parsed = parseTranslations(
            raw = "1. Hola\nmundo\n2. Adios",
            units = units,
            provider = ProviderId.GOOGLE_PA,
        )

        assertEquals(mapOf("a" to "Hola\nmundo", "b" to "Adios"), parsed)
    }

    // OpenAI non-success response shapes.
    @Test
    fun openAiContentFilterIsBlocked() = networkTest {
        val manager = managerFor(
            body = """{"incomplete_details":{"reason":"content_filter"},"output":[]}""",
            config = config(ProviderId.OPENAI, "https://test/openai"),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.ContentBlocked>(error)
    }

    @Test
    fun openAiRefusalIsBlocked() = networkTest {
        val body = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}"""
        val manager = managerFor(
            body = body,
            config = config(ProviderId.OPENAI, "https://test/openai"),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.ContentBlocked>(error)
    }

    @Test
    fun openAiFailedStatusIsInvalidResponse() = networkTest {
        val manager = managerFor(
            body = """{"status":"failed","error":{"message":"upstream broke"}}""",
            config = config(ProviderId.OPENAI, "https://test/openai"),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.InvalidResponse>(error)
        assertTrue(error.message.contains("upstream broke"))
    }

    @Test
    fun openAiMaxOutputTokensIsTruncated() = networkTest {
        val manager = managerFor(
            body = """{"incomplete_details":{"reason":"max_output_tokens"}}""",
            config = config(ProviderId.OPENAI, "https://test/openai"),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.Truncated>(error)
    }

    // Invalid API key reported as HTTP 400 rotates to the next key.
    @Test
    fun invalidKeyOnBadRequestRotates() = networkTest {
        val bodies = listOf(
            """{"error":{"code":"API_KEY_INVALID"}}""",
            """{"error":{"message":"API key not valid"}}""",
            """{"error":{"message":"invalid api key"}}""",
            """{"error":{"status":"PERMISSION_DENIED"}}""",
        )
        for (body in bodies) {
            val usedKeys = mutableListOf<String>()
            val engine = MockEngine { request ->
                usedKeys += request.headers[HttpHeaders.Authorization].orEmpty()
                if (usedKeys.size == 1) {
                    respond(content = body, status = HttpStatusCode.BadRequest)
                } else {
                    respond(
                        content = """{"output_text":${JsonPrimitive("""{"translations":[{"id":"1","text":"Hola"}]}""")}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            val manager = TranslationManagerImpl(
                client = HttpClient(engine),
                configs = listOf(
                    TranslationProviderConfig(
                        provider = ProviderId.OPENAI,
                        apiKeys = listOf("bad", "good"),
                        baseUrl = "https://test/openai",
                        maxAttempts = 2,
                    ),
                ),
            )

            val result = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireOk()

            assertEquals("Hola", result.translations["1"], body)
            assertEquals(listOf("Bearer bad", "Bearer good"), usedKeys, body)
        }
    }

    @Test
    fun otherBadRequestStaysInvalidRequest() = networkTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(content = """{"error":"bad payload"}""", status = HttpStatusCode.BadRequest)
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(
                TranslationProviderConfig(
                    provider = ProviderId.OPENAI,
                    apiKeys = listOf("one", "two"),
                    baseUrl = "https://test/openai",
                    maxAttempts = 2,
                ),
            ),
        )

        val error = manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireErr()

        assertIs<TranslationError.InvalidRequest>(error)
        assertEquals(1, calls)
    }

    // Provider config must never leak API keys.
    @Test
    fun providerConfigMasksApiKeys() {
        val config = TranslationProviderConfig(
            provider = ProviderId.OPENAI,
            apiKeys = listOf("sk-secret-one", "sk-secret-two"),
            model = "gpt-4o-mini",
        )

        val text = config.toString()

        assertFalse(text.contains("sk-secret-one"))
        assertFalse(text.contains("sk-secret-two"))
        assertTrue(text.contains("***"))
        assertTrue(text.contains("gpt-4o-mini"))
    }

    // Gemini requests disable the five safety categories.
    @Test
    fun geminiSendsSafetySettings() = networkTest {
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement(
                request.body.toByteArray().decodeToString(),
            ).jsonObject
            captured = body
            val input = body.getValue("contents").jsonArray.single()
                .jsonObject.getValue("parts").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content
            respond(
                content = geminiBody(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val manager = TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GEMINI, "https://test/gemini")),
        )

        manager.translateBatch(request(ProviderId.GEMINI, "Hello")).requireOk()

        val settings = captured?.getValue("safetySettings")?.jsonArray ?: fail("no safetySettings")
        assertEquals(5, settings.size)
        for (setting in settings) {
            assertEquals("BLOCK_NONE", setting.jsonObject.getValue("threshold").jsonPrimitive.content)
        }
        assertTrue(
            settings.any {
                it.jsonObject.getValue("category").jsonPrimitive.content ==
                    "HARM_CATEGORY_CIVIC_INTEGRITY"
            },
        )
    }

    private fun networkTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun managerFor(
        body: String,
        config: TranslationProviderConfig,
    ): TranslationManagerImpl = TranslationManagerImpl(
        client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
        configs = listOf(config),
    )

    private fun config(
        provider: ProviderId,
        baseUrl: String,
        maxAttempts: Int = 1,
        maxCharsPerRequest: Int? = null,
    ): TranslationProviderConfig = TranslationProviderConfig(
        provider = provider,
        apiKeys = listOf("test-key"),
        baseUrl = baseUrl,
        maxAttempts = maxAttempts,
        maxCharsPerRequest = maxCharsPerRequest,
    )

    private fun detectionManager(engine: MockEngine): TranslationManagerImpl =
        TranslationManagerImpl(
            client = HttpClient(engine),
            configs = listOf(config(ProviderId.GOOGLE_FREE, "https://test/free")),
        )

    private fun translatingEngine(
        provider: ProviderId,
        onInput: (String) -> Unit = {},
    ): MockEngine = MockEngine { request ->
        val input = when (provider) {
            ProviderId.GEMINI -> geminiInput(request)
            ProviderId.OPENAI -> openAiInput(request)
            else -> error("unsupported provider: $provider")
        }
        onInput(input)
        val translated = translateInput(input)
        val body = when (provider) {
            ProviderId.GEMINI -> geminiBody(translated)
            else -> """{"output_text":${JsonPrimitive(translated)}}"""
        }
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun idsIn(input: String): List<String> =
        Json.parseToJsonElement(input).jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }

    private suspend fun geminiInput(request: HttpRequestData): String {
        val root = Json.parseToJsonElement(
            request.body.toByteArray().decodeToString(),
        ).jsonObject
        return root.getValue("contents").jsonArray.single()
            .jsonObject.getValue("parts").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content
    }

    private suspend fun openAiInput(request: HttpRequestData): String {
        val root = Json.parseToJsonElement(
            request.body.toByteArray().decodeToString(),
        ).jsonObject
        return root.getValue("input").jsonPrimitive.content
    }

    private fun translateInput(input: String): String {
        val units = Json.parseToJsonElement(input).jsonArray
        return buildJsonObject {
            put(
                "translations",
                buildJsonArray {
                    for (unit in units) {
                        val obj = unit.jsonObject
                        add(
                            buildJsonObject {
                                put("id", obj.getValue("id").jsonPrimitive.content)
                                put("text", "T:" + obj.getValue("text").jsonPrimitive.content)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun geminiBody(inner: String): String =
        """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(inner)}}]},"finishReason":"STOP"}]}"""

    private fun request(
        provider: ProviderId,
        units: List<TranslationUnit>,
        source: LanguageCode? = LanguageCode.parse("en"),
    ): BatchTranslationRequest = BatchTranslationRequest(
        bookId = 1,
        chapterKey = "chapter-1",
        units = units,
        source = source,
        target = LanguageCode.parse("es"),
        provider = provider,
    )

    private fun request(
        provider: ProviderId,
        vararg texts: String,
        source: LanguageCode? = LanguageCode.parse("en"),
    ): BatchTranslationRequest = BatchTranslationRequest(
        bookId = 1,
        chapterKey = "chapter-1",
        units = texts.mapIndexed { index, text -> TranslationUnit("${index + 1}", text) },
        source = source,
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
