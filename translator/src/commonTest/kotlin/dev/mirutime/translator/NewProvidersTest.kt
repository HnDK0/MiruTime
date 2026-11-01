package dev.mirutime.translator

import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.internal.KtorTranslationHttpClient
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.provider.AnthropicProvider
import dev.mirutime.translator.provider.DEFAULT_GOOGLE_PA_API_KEY
import dev.mirutime.translator.provider.DeepLProvider
import dev.mirutime.translator.provider.YandexCloudProvider
import dev.mirutime.translator.provider.YandexFreeProvider
import dev.mirutime.translator.provider.YandexPageProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.http.Parameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NewProvidersTest {
    @Test
    fun providerDefaultsUseTestedConcurrency() {
        assertEquals(8, TranslationProviderConfig(ProviderId.GOOGLE_FREE).chunkConcurrency)
        assertEquals(8, TranslationProviderConfig(ProviderId.GOOGLE_PA).chunkConcurrency)
        assertEquals(8, TranslationProviderConfig(ProviderId.YANDEX_PA).chunkConcurrency)
        assertEquals(1, TranslationProviderConfig(ProviderId.OPENAI).chunkConcurrency)
        assertEquals(1, TranslationProviderConfig(ProviderId.ANTHROPIC).chunkConcurrency)
        assertEquals(180.seconds, TranslationProviderConfig(ProviderId.OPENAI).timeout)
    }

    @Test
    fun googlePaFallsBackToPublicKeyWhenNoneConfigured() = networkTest {
        var usedKey: String? = null
        val engine = MockEngine { request ->
            usedKey = request.headers["X-Goog-Api-Key"]
            respond(
                content = """[["Hola"]]""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.GOOGLE_PA,
                baseUrl = "https://test/pa",
                keys = emptyList(),
            ),
        )

        val result = manager.translateBatch(request(ProviderId.GOOGLE_PA, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertEquals(DEFAULT_GOOGLE_PA_API_KEY, usedKey)
    }

    // OpenAI-compatible: path, auth, body shape and actual ids.
    @Test
    fun openAiCompatibleSendsChatCompletionsRequestAndPreservesIds() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        var url = ""
        var auth = ""
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization].orEmpty()
            val root = jsonBody(request)
            captured = root
            val input = userContent(root)
            respond(
                content = chatResponse(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                maxOutputTokens = 256,
            ),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, units)).requireOk()

        assertEquals("https://llm.test/chat/completions", url)
        assertEquals("Bearer test-key", auth)
        val body = captured ?: fail("no request body")
        assertEquals("local-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("256", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(
            "json_object",
            body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(listOf("a", "b"), idsIn(userContent(body)))
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
        assertTrue(result.promptHash.isNotEmpty())
    }

    @Test
    fun openAiCompatibleDoesNotDoubleAPastedEndpoint() = networkTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(
                content = chatResponse("""{"translations":[{"id":"1","text":"Hola"}]}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "http://llm.test/v1/chat/completions/",
                model = "local-model",
            ),
        )

        manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, "Hello")).requireOk()

        assertEquals("http://llm.test/v1/chat/completions", url)
    }

    @Test
    fun openAiCompatibleUsesJsonSchemaWhenEnabled() = networkTest {
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            val root = jsonBody(request)
            captured = root
            respond(
                content = chatResponse("""{"translations":[{"id":"1","text":"Hola"}]}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                useJsonSchema = true,
            ),
        )

        manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, "Hello")).requireOk()

        val format = (captured ?: fail("no request body"))
            .getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val schema = format.getValue("json_schema").jsonObject
        assertEquals("translations", schema.getValue("name").jsonPrimitive.content)
        assertTrue(schema.getValue("strict").jsonPrimitive.content.toBoolean())
        assertEquals(
            "translations",
            schema.getValue("schema").jsonObject
                .getValue("required").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun openAiCompatibleParsesArrayContent() = networkTest {
        // Split into two text parts: the provider must concatenate them.
        val inner = """{"translations":[{"id":"1","text":"Hola"}]}"""
        val body = buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", inner.take(20))
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", inner.drop(20))
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    put("image_url", "ignored")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("finish_reason", "stop")
                        },
                    )
                },
            )
        }.toString()
        val manager = manager(
            MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) },
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
            ),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
    }

    @Test
    fun openAiCompatibleFallsBackToChoiceText() = networkTest {
        val body = buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("text", """{"translations":[{"id":"1","text":"Hola"}]}""")
                            put("finish_reason", "stop")
                        },
                    )
                },
            )
        }.toString()
        val manager = manager(
            MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) },
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
            ),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, "Hello")).requireOk()

        assertEquals("Hola", result.translations["1"])
    }

    @Test
    fun openAiCompatibleSurfacesErrorBodyMessage() = networkTest {
        val manager = manager(
            MockEngine {
                respond(
                    """{"error":{"message":"model 'nope' not found","type":"invalid_request_error"}}""",
                    HttpStatusCode.BadRequest,
                )
            },
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
            ),
        )

        val error = manager.translateBatch(
            request(ProviderId.OPENAI_COMPATIBLE, "Hello"),
        ).requireErr()

        assertIs<TranslationError.InvalidRequest>(error)
        assertTrue(error.message.contains("model 'nope' not found"), error.message)
        assertTrue(!error.message.contains("invalid_request_error"), error.message)
    }

    @Test
    fun openAiCompatibleRedactsAnHtmlErrorBody() = networkTest {
        val manager = manager(
            MockEngine {
                respond(
                    "<html><body>Cannot POST https://llm.test/private?key=abc</body></html>",
                    HttpStatusCode.NotFound,
                )
            },
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
            ),
        )

        val error = manager.translateBatch(
            request(ProviderId.OPENAI_COMPATIBLE, "Hello"),
        ).requireErr()

        assertIs<TranslationError.InvalidRequest>(error)
        assertTrue(error.message.contains("HTTP 404"), error.message)
        assertTrue(error.message.contains("Cannot POST"), error.message)
        assertTrue(!error.message.contains("https://llm.test"), error.message)
    }

    @Test
    fun openAiCompatibleMapsFinishReasons() = networkTest {
        val cases = listOf<Pair<String, (MiruError) -> Boolean>>(
            "length" to { it is TranslationError.Truncated },
            "content_filter" to { it is TranslationError.ContentBlocked },
        )
        for ((reason, isExpected) in cases) {
            val body = buildJsonObject {
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("finish_reason", reason)
                                put("message", buildJsonObject { put("content", "") })
                            },
                        )
                    },
                )
            }.toString()
            val manager = manager(
                MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) },
                config(
                    ProviderId.OPENAI_COMPATIBLE,
                    baseUrl = "https://llm.test",
                    model = "local-model",
                ),
            )

            val error = manager.translateBatch(
                request(ProviderId.OPENAI_COMPATIBLE, "Hello"),
            ).requireErr()

            assertTrue(isExpected(error), "wrong error for $reason: $error")
        }
    }

    @Test
    fun openAiCompatibleRequiresBaseUrlAndModel() = networkTest {
        val manager = manager(
            MockEngine { fail("must not be called") },
            config(ProviderId.OPENAI_COMPATIBLE, model = "local-model"),
        )

        val missingBase = manager.translateBatch(
            request(ProviderId.OPENAI_COMPATIBLE, "Hello"),
        ).requireErr()
        assertIs<TranslationError.InvalidRequest>(missingBase)
        assertTrue(missingBase.message.contains("baseUrl"))

        val withBase = manager(
            MockEngine { fail("must not be called") },
            config(ProviderId.OPENAI_COMPATIBLE, baseUrl = "https://llm.test"),
        )
        val missingModel = withBase.translateBatch(
            request(ProviderId.OPENAI_COMPATIBLE, "Hello"),
        ).requireErr()
        assertIs<TranslationError.InvalidRequest>(missingModel)
        assertTrue(missingModel.message.contains("model"))
    }

    @Test
    fun openAiCompatibleHonorsMaxCharsPerRequest() = networkTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            respond(
                content = chatResponse(translateInput(userContent(jsonBody(request)))),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                maxCharsPerRequest = 5,
            ),
        )
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, units)).requireOk()

        assertEquals(2, calls)
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
    }

    @Test
    fun openAiCompatibleSendsWholeChapterInOneRequest() = networkTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            respond(
                content = chatResponse(translateInput(userContent(jsonBody(request)))),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                maxCharsPerRequest = 10_000,
            ),
        )
        val units = (0 until 10).map { TranslationUnit("p$it", "Paragraph $it") }

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, units)).requireOk()

        assertEquals(1, calls)
        units.forEach { unit ->
            assertEquals("T:${unit.text}", result.translations[unit.id])
        }
    }

    @Test
    fun openAiCompatibleSplitsLongUnitAndRestoresOriginalId() = networkTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val input = userContent(jsonBody(request))
            Json.parseToJsonElement(input).jsonArray.forEach { item ->
                val text = item.jsonObject.getValue("text").jsonPrimitive.content
                assertTrue(text.length <= 20, "request part exceeds budget: ${text.length}")
            }
            respond(
                content = chatResponse(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                maxCharsPerRequest = 20,
            ),
        )
        val unit = TranslationUnit(
            "p",
            "First sentence. Second sentence. Third sentence.",
        )

        val result = manager.translateBatch(
            request(ProviderId.OPENAI_COMPATIBLE, listOf(unit)),
        ).requireOk()

        assertEquals(setOf("p"), result.translations.keys)
        assertEquals(
            "T:First sentence. T:Second sentence. T:Third sentence.",
            result.translations["p"],
        )
        assertEquals(3, calls)
    }

    @Test
    fun openAiCompatibleUsesAutoBudgetAndDefaultMaxOutputWhenUnset() = networkTest {
        var calls = 0
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            calls++
            val root = jsonBody(request)
            captured = root
            respond(
                content = chatResponse(translateInput(userContent(root))),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
            ),
        )
        val units = (0 until 4).map { TranslationUnit("u$it", "x".repeat(3_000)) }

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, units)).requireOk()

        // Auto budget for the OpenAI-compatible default output (4 096 tokens) is
        // under 12k characters, so this chapter must take two requests.
        assertEquals(2, calls)
        val body = captured ?: fail("no request body")
        assertEquals("4096", body.getValue("max_tokens").jsonPrimitive.content)
        units.forEach { unit ->
            assertEquals("T:${unit.text}", result.translations[unit.id])
        }
    }

    @Test
    fun llmBudgetUsesTargetLanguageForRequestSize() = networkTest {
        val units = (0 until 2).map { TranslationUnit("u$it", "x".repeat(1_000)) }
        for ((target, expectedCalls) in listOf("en" to 1, "zh" to 2)) {
            var calls = 0
            val engine = MockEngine { request ->
                calls++
                val root = jsonBody(request)
                respond(
                    content = chatResponse(translateInput(userContent(root))),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val manager = manager(
                engine,
                config(
                    ProviderId.OPENAI_COMPATIBLE,
                    baseUrl = "https://llm.test",
                    model = "local-model",
                ),
            )

            val result = manager.translateBatch(
                request(
                    ProviderId.OPENAI_COMPATIBLE,
                    units,
                    target = LanguageCode.parse(target),
                ),
            ).requireOk()

            assertEquals(expectedCalls, calls, "target=$target")
            units.forEach { unit ->
                assertEquals("T:${unit.text}", result.translations[unit.id])
            }
        }
    }

    @Test
    fun geminiSendsDefaultMaxOutputWhenUnset() = networkTest {        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            val root = jsonBody(request)
            captured = root
            val input = root.getValue("contents").jsonArray.single()
                .jsonObject.getValue("parts").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content
            respond(
                content = geminiResponse(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.GEMINI, baseUrl = "https://gemini.test"),
        )

        manager.translateBatch(request(ProviderId.GEMINI, "Hello")).requireOk()

        val generation = (captured ?: fail("no request body"))
            .getValue("generationConfig").jsonObject
        assertEquals("65536", generation.getValue("maxOutputTokens").jsonPrimitive.content)
    }

    @Test
    fun openAiSendsDefaultMaxOutputWhenUnset() = networkTest {
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            val root = jsonBody(request)
            captured = root
            val input = root.getValue("input").jsonPrimitive.content
            respond(
                content = """{"output_text":${JsonPrimitive(translateInput(input))}}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.OPENAI, baseUrl = "https://openai.test"),
        )

        manager.translateBatch(request(ProviderId.OPENAI, "Hello")).requireOk()

        val body = captured ?: fail("no request body")
        assertEquals("16384", body.getValue("max_output_tokens").jsonPrimitive.content)
    }

    @Test
    fun googleFreeSplitsAtLineBoundaryAndRestoresOriginalUnit() = networkTest {
        val text = "Hello world\nSecond line"
        val sent = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.parameters["q"].orEmpty()
            sent += query
            respond(
                content = """[[[${JsonPrimitive("T:$query")}]]]""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.GOOGLE_FREE, maxCharsPerRequest = 15),
        )

        val result = manager.translateBatch(
            request(ProviderId.GOOGLE_FREE, listOf(TranslationUnit("orig", text))),
        ).requireOk()

        assertEquals(setOf("orig"), result.translations.keys)
        assertEquals("T:Hello world\nT:Second line", result.translations["orig"])
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.length <= 15 }, "sent lengths: ${sent.map { it.length }}")
    }

    @Test
    fun openAiCompatibleRunsBatchesConcurrentlyAndPreservesOrder() = networkTest {
        val probe = InFlightProbe(expected = 2)
        val engine = MockEngine { request ->
            val input = userContent(jsonBody(request))
            probe.track {
                respond(
                    content = chatResponse(translateInput(input)),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
        }
        val manager = manager(
            engine,
            config(
                ProviderId.OPENAI_COMPATIBLE,
                baseUrl = "https://llm.test",
                model = "local-model",
                maxCharsPerRequest = 5,
                chunkConcurrency = 2,
            ),
        )
        val units = listOf(
            TranslationUnit("a", "Hello"),
            TranslationUnit("b", "World"),
            TranslationUnit("c", "Again"),
        )

        val result = manager.translateBatch(request(ProviderId.OPENAI_COMPATIBLE, units)).requireOk()

        assertEquals(2, probe.maxActive)
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
        assertEquals("T:Again", result.translations["c"])
    }

    // Anthropic: path, headers, structured output config and content parsing.
    @Test
    fun anthropicSendsMessagesRequestAndParsesContent() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        var url = ""
        var apiKey = ""
        var version = ""
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            apiKey = request.headers["x-api-key"].orEmpty()
            version = request.headers["anthropic-version"].orEmpty()
            val root = jsonBody(request)
            captured = root
            val input = root.getValue("messages").jsonArray.single()
                .jsonObject.getValue("content").jsonPrimitive.content
            respond(
                content = anthropicResponse(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.ANTHROPIC, baseUrl = "https://anthropic.test"),
        )

        val result = manager.translateBatch(request(ProviderId.ANTHROPIC, units)).requireOk()

        assertEquals("https://anthropic.test/v1/messages", url)
        assertEquals("test-key", apiKey)
        assertEquals("2023-06-01", version)
        val body = captured ?: fail("no request body")
        assertEquals("claude-sonnet-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals("65536", body.getValue("max_tokens").jsonPrimitive.content)
        assertTrue(body.getValue("system").jsonPrimitive.content.contains("es"))
        assertEquals("user", body.getValue("messages").jsonArray.single().jsonObject["role"]
            ?.jsonPrimitive?.content)
        assertEquals(listOf("a", "b"), idsIn(anthropicInput(body)))
        val format = body.getValue("output_config").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertTrue(format.containsKey("schema"))
        assertEquals("T:Hello", result.translations["a"])
        assertEquals("T:World", result.translations["b"])
        assertTrue(result.promptHash.isNotEmpty())
    }

    @Test
    fun anthropicHonorsConfiguredModelAndOutputLimit() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"))
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            val root = jsonBody(request)
            captured = root
            val input = root.getValue("messages").jsonArray.single()
                .jsonObject.getValue("content").jsonPrimitive.content
            respond(
                content = anthropicResponse(translateInput(input)),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.ANTHROPIC,
                baseUrl = "https://anthropic.test",
                model = "claude-configured",
                maxOutputTokens = 1234,
            ),
        )

        manager.translateBatch(request(ProviderId.ANTHROPIC, units)).requireOk()

        val body = captured ?: fail("no request body")
        assertEquals("claude-configured", body.getValue("model").jsonPrimitive.content)
        assertEquals("1234", body.getValue("max_tokens").jsonPrimitive.content)
    }

    @Test
    fun anthropicContentRefusalIsBlocked() = networkTest {
        val body = buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "refusal"); put("refusal", "no") })
                },
            )
            put("stop_reason", "end_turn")
        }.toString()
        val manager = manager(
            MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) },
            config(ProviderId.ANTHROPIC, baseUrl = "https://anthropic.test"),
        )

        val error = manager.translateBatch(request(ProviderId.ANTHROPIC, "Hello")).requireErr()

        assertIs<TranslationError.ContentBlocked>(error)
    }

    @Test
    fun anthropicStopReasonsAreMapped() = networkTest {
        val refused = buildJsonObject {
            put("content", buildJsonArray {})
            put("stop_reason", "refusal")
        }.toString()
        val truncated = buildJsonObject {
            put("content", buildJsonArray {})
            put("stop_reason", "max_tokens")
        }.toString()

        val refusedError = manager(
            MockEngine { respond(refused, HttpStatusCode.OK, jsonHeaders) },
            config(ProviderId.ANTHROPIC, baseUrl = "https://anthropic.test"),
        ).translateBatch(request(ProviderId.ANTHROPIC, "Hello")).requireErr()
        assertIs<TranslationError.ContentBlocked>(refusedError)

        val truncatedError = manager(
            MockEngine { respond(truncated, HttpStatusCode.OK, jsonHeaders) },
            config(ProviderId.ANTHROPIC, baseUrl = "https://anthropic.test"),
        ).translateBatch(request(ProviderId.ANTHROPIC, "Hello")).requireErr()
        assertIs<TranslationError.Truncated>(truncatedError)
    }

    // DeepL: free endpoint selection, request shape, order and quota mapping.
    @Test
    fun deepLUsesFreeEndpointForFxKeysAndKeepsOrder() = networkTest {
        var url = ""
        var auth = ""
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization].orEmpty()
            val root = jsonBody(request)
            captured = root
            respond(
                content = """{"translations":[
                    {"detected_source_language":"EN","text":"Hola"},
                    {"detected_source_language":"EN","text":"Mundo"}
                ]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.DEEPL, keys = listOf("secret:fx")),
        )
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))

        val result = manager.translateBatch(request(ProviderId.DEEPL, units)).requireOk()

        assertEquals("https://api-free.deepl.com/v2/translate", url)
        assertEquals("DeepL-Auth-Key secret:fx", auth)
        val body = captured ?: fail("no request body")
        assertEquals(
            listOf("Hello", "World"),
            body.getValue("text").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("ES", body.getValue("target_lang").jsonPrimitive.content)
        assertEquals("EN", body.getValue("source_lang").jsonPrimitive.content)
        assertEquals("Hola", result.translations["a"])
        assertEquals("Mundo", result.translations["b"])
        assertEquals("", result.promptHash)
    }

    @Test
    fun deepLOmitsSourceLanguageWhenUnknown() = networkTest {
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            captured = jsonBody(request)
            respond(
                content = """{"translations":[{"text":"Hola"}]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = DeepLProvider(
            KtorTranslationHttpClient(HttpClient(engine)),
            config(ProviderId.DEEPL, keys = listOf("key")),
            TimeSource.Monotonic,
        )

        provider.translate(ProviderRequest(listOf(TranslationUnit("1", "Hello")), null, LanguageCode.parse("es"), null))

        val body = captured ?: fail("no request body")
        assertTrue(!body.containsKey("source_lang"))
        assertEquals("ES", body.getValue("target_lang").jsonPrimitive.content)
    }

    @Test
    fun deepLWorksWithoutConfiguredDetectProvider() = networkTest {
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            captured = jsonBody(request)
            respond(
                content = """{"translations":[{"text":"Hola"}]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.DEEPL, keys = listOf("key:fx")),
        )

        val result = manager.translateBatch(
            request(ProviderId.DEEPL, "Hello", source = null),
        ).requireOk()

        assertEquals("Hola", result.translations["1"])
        assertTrue(!(captured ?: fail("no request body")).containsKey("source_lang"))
    }

    @Test
    fun deepLQuotaIsTyped() = networkTest {
        val manager = manager(
            MockEngine { respond("quota exceeded", HttpStatusCode(456, "Quota exceeded")) },
            config(ProviderId.DEEPL, keys = listOf("key:fx")),
        )

        val error = manager.translateBatch(request(ProviderId.DEEPL, "Hello")).requireErr()

        assertIs<TranslationError.Quota>(error)
    }

    @Test
    fun deepLReportsDetectedSource() = networkTest {
        val engine = MockEngine {
            respond(
                content = """{"translations":[{"detected_source_language":"EN","text":"Hola"}]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = DeepLProvider(
            KtorTranslationHttpClient(HttpClient(engine)),
            config(ProviderId.DEEPL, keys = listOf("key")),
            TimeSource.Monotonic,
        )

        val result = provider.translate(
            ProviderRequest(listOf(TranslationUnit("1", "Hello")), null, LanguageCode.parse("es"), null),
        )

        assertEquals(LanguageCode.parse("en"), result.detectedSource)
    }

    // Yandex: folderId, texts and response order.
    @Test
    fun yandexSendsFolderIdAndTexts() = networkTest {
        var url = ""
        var auth = ""
        var captured: JsonObject? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization].orEmpty()
            val root = jsonBody(request)
            captured = root
            respond(
                content = """{"translations":[
                    {"text":"Hola","detectedLanguageCode":"en"},
                    {"text":"Mundo","detectedLanguageCode":"en"}
                ]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(
                ProviderId.YANDEX_CLOUD,
                baseUrl = "https://yandex.test/translate/v2/translate",
                folderId = "folder-1",
            ),
        )
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))

        val result = manager.translateBatch(request(ProviderId.YANDEX_CLOUD, units)).requireOk()

        assertEquals("https://yandex.test/translate/v2/translate", url)
        assertEquals("Api-Key test-key", auth)
        val body = captured ?: fail("no request body")
        assertEquals(
            listOf("Hello", "World"),
            body.getValue("texts").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("es", body.getValue("targetLanguageCode").jsonPrimitive.content)
        assertEquals("en", body.getValue("sourceLanguageCode").jsonPrimitive.content)
        assertEquals("PLAIN_TEXT", body.getValue("format").jsonPrimitive.content)
        assertEquals("folder-1", body.getValue("folderId").jsonPrimitive.content)
        assertEquals("Hola", result.translations["a"])
        assertEquals("Mundo", result.translations["b"])
        assertEquals("", result.promptHash)
    }

    @Test
    fun yandexReportsDetectedSource() = networkTest {
        val engine = MockEngine {
            respond(
                content = """{"translations":[{"text":"Hola","detectedLanguageCode":"en"}]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = YandexCloudProvider(
            KtorTranslationHttpClient(HttpClient(engine)),
            config(ProviderId.YANDEX_CLOUD, baseUrl = "https://yandex.test/translate"),
            TimeSource.Monotonic,
        )

        val result = provider.translate(
            ProviderRequest(listOf(TranslationUnit("1", "Hello")), null, LanguageCode.parse("es"), null),
        )

        assertEquals(LanguageCode.parse("en"), result.detectedSource)
    }

    // Titles: new LLM providers still fall back strictly to PA -> Free.
    @Test
    fun newLlmProvidersFallBackToGooglePaForTitles() = networkTest {
        for (provider in listOf(ProviderId.OPENAI_COMPATIBLE, ProviderId.ANTHROPIC)) {
            val manager = manager(
                MockEngine {
                    respond("""[["Hola"]]""", HttpStatusCode.OK, jsonHeaders)
                },
                config(ProviderId.GOOGLE_PA, baseUrl = "https://test/pa"),
                config(provider, baseUrl = "https://llm.test", model = "m"),
            )

            val result = manager.translateTitle(
                TitleTranslationRequest(
                    bookId = 1,
                    title = "Hello",
                    source = LanguageCode.parse("en"),
                    target = LanguageCode.parse("es"),
                    provider = provider,
                ),
            ).requireOk()

            assertEquals("Hola", result, "title fallback for $provider")
        }
    }

    // Experimental Yandex: keyless android/browser endpoints, fixed request shapes.
    @Test
    fun yandexFreeSendsAndroidRequestAndMapsIds() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        var method: HttpMethod? = null
        var srv = ""
        var format = ""
        var lang = ""
        var requestId = ""
        var authorization: String? = null
        var userAgent: String? = null
        var texts = emptyList<String>()
        val engine = MockEngine { request ->
            method = request.method
            srv = request.url.parameters["srv"].orEmpty()
            format = request.url.parameters["format"].orEmpty()
            lang = request.url.parameters["lang"].orEmpty()
            requestId = request.url.parameters["id"].orEmpty()
            authorization = request.headers[HttpHeaders.Authorization]
            userAgent = request.headers[HttpHeaders.UserAgent]
            texts = formBody(request).getAll("text").orEmpty()
            respond(
                content = """{"code":200,"lang":"en-es","text":["Hola","Mundo"]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.YANDEX_FREE, baseUrl = "https://yandex.test/tr"),
        )

        val result = manager.translateBatch(request(ProviderId.YANDEX_FREE, units)).requireOk()

        assertEquals(HttpMethod.Post, method)
        assertEquals("android", srv)
        assertEquals("text", format)
        assertEquals("en-es", lang)
        assertTrue(Regex("[0-9a-f]{32}-\\d+-0").matches(requestId), requestId)
        // The plain Ktor adapter must not inject a User-Agent; core supplies it in production.
        assertEquals(null, userAgent)
        assertEquals(null, authorization)
        assertEquals(listOf("Hello", "World"), texts)
        assertEquals("Hola", result.translations["a"])
        assertEquals("Mundo", result.translations["b"])
        assertEquals("", result.promptHash)
    }

    @Test
    fun yandexFreeUsesPrimaryTargetWhenSourceIsUnknown() = networkTest {
        var lang = ""
        val engine = MockEngine { request ->
            lang = request.url.parameters["lang"].orEmpty()
            respond(
                content = """{"code":200,"text":["Hola"]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = YandexFreeProvider(
            KtorTranslationHttpClient(HttpClient(engine)),
            config(ProviderId.YANDEX_FREE),
            TimeSource.Monotonic,
        )

        provider.translate(
            ProviderRequest(
                units = listOf(TranslationUnit("1", "Hello")),
                source = null,
                target = LanguageCode.parse("es-MX"),
                prompt = null,
            ),
        )

        assertEquals("es", lang)
    }

    @Test
    fun yandexFreeAcceptsSingleTextString() = networkTest {
        val engine = MockEngine {
            respond(
                content = """{"code":200,"lang":"en-es","text":"Hola"}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val provider = YandexFreeProvider(
            KtorTranslationHttpClient(HttpClient(engine)),
            config(ProviderId.YANDEX_FREE),
            TimeSource.Monotonic,
        )

        val result = provider.translate(
            ProviderRequest(
                units = listOf(TranslationUnit("1", "Hello")),
                source = LanguageCode.parse("en"),
                target = LanguageCode.parse("es"),
                prompt = null,
            ),
        )

        assertEquals("Hola", result.translations["1"])
    }

    @Test
    fun yandexFreeChunksAboveTenThousandCharacters() = networkTest {
        val texts = listOf("a".repeat(4_000), "b".repeat(4_000), "c".repeat(4_000))
        var calls = 0
        val groupSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            calls++
            val sent = formBody(request).getAll("text").orEmpty()
            groupSizes += sent.sumOf { it.length }
            respond(
                content = buildJsonObject {
                    put("code", 200)
                    put(
                        "text",
                        buildJsonArray {
                            for (text in sent) add(JsonPrimitive("T:" + text.first()))
                        },
                    )
                }.toString(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(engine, config(ProviderId.YANDEX_FREE))
        val units = texts.mapIndexed { index, text -> TranslationUnit("u$index", text) }

        val result = manager.translateBatch(request(ProviderId.YANDEX_FREE, units)).requireOk()

        assertEquals(2, calls)
        assertTrue(groupSizes.all { it <= 10_000 }, "group sizes: $groupSizes")
        assertEquals("T:a", result.translations["u0"])
        assertEquals("T:b", result.translations["u1"])
        assertEquals("T:c", result.translations["u2"])
    }

    @Test
    fun googleFreeRunsChunkWindowsConcurrentlyAndPreservesOrder() = networkTest {
        val texts = listOf("a".repeat(4_000), "b".repeat(4_000), "c".repeat(4_000))
        val probe = InFlightProbe(expected = 2)
        val engine = MockEngine { request ->
            val sent = formBody(request).getAll("q").orEmpty().single()
            probe.track {
                respond(
                    content = """[[["T:${sent.first()}", "en", null, null]], null, "en"]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
        }
        val manager = manager(
            engine,
            config(ProviderId.GOOGLE_FREE, chunkConcurrency = 2),
        )
        val units = texts.mapIndexed { index, text -> TranslationUnit("u$index", text) }

        val result = manager.translateBatch(request(ProviderId.GOOGLE_FREE, units)).requireOk()

        assertEquals(2, probe.maxActive)
        assertEquals("T:a", result.translations["u0"])
        assertEquals("T:b", result.translations["u1"])
        assertEquals("T:c", result.translations["u2"])
    }

    @Test
    fun yandexPageSendsBrowserRequestWithSidAndMapsIds() = networkTest {
        val units = listOf(TranslationUnit("a", "Hello"), TranslationUnit("b", "World"))
        var method: HttpMethod? = null
        var srv = ""
        var format = ""
        var options = ""
        var lang = ""
        var requestId = ""
        var authorization: String? = null
        var texts = emptyList<String>()
        val engine = MockEngine { request ->
            method = request.method
            srv = request.url.parameters["srv"].orEmpty()
            format = request.url.parameters["format"].orEmpty()
            options = request.url.parameters["options"].orEmpty()
            lang = request.url.parameters["lang"].orEmpty()
            requestId = request.url.parameters["id"].orEmpty()
            authorization = request.headers[HttpHeaders.Authorization]
            texts = request.url.parameters.getAll("text").orEmpty()
            respond(
                content = """{"code":200,"lang":"en-es","text":["Hola","Mundo"],"align":[[0,1],[2,3]]}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.YANDEX_PA, baseUrl = "https://yandex.test/page"),
        )

        val result = manager.translateBatch(request(ProviderId.YANDEX_PA, units)).requireOk()

        assertEquals(HttpMethod.Get, method)
        assertEquals("yabrowser", srv)
        assertEquals("html", format)
        assertEquals("2", options)
        assertEquals("en-es", lang)
        assertTrue(Regex("[0-9a-f]{16}-\\d+-0").matches(requestId), requestId)
        assertEquals(null, authorization)
        assertEquals(listOf("Hello", "World"), texts)
        assertEquals("Hola", result.translations["a"])
        assertEquals("Mundo", result.translations["b"])
        assertEquals("", result.promptHash)
    }

    @Test
    fun yandexPageChunksAtSafetyLimit() = networkTest {
        val texts = listOf("a".repeat(1_500), "b".repeat(1_500))
        var calls = 0
        val groupSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            calls++
            val sent = request.url.parameters.getAll("text").orEmpty()
            groupSizes += sent.sumOf { it.length }
            respond(
                content = buildJsonObject {
                    put("code", 200)
                    put(
                        "text",
                        buildJsonArray {
                            for (text in sent) add(JsonPrimitive("T:" + text.first()))
                        },
                    )
                }.toString(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(engine, config(ProviderId.YANDEX_PA))
        val units = texts.mapIndexed { index, text -> TranslationUnit("u$index", text) }

        val result = manager.translateBatch(request(ProviderId.YANDEX_PA, units)).requireOk()

        assertEquals(2, calls)
        assertTrue(groupSizes.all { it <= 2_000 }, "group sizes: $groupSizes")
        assertEquals("T:a", result.translations["u0"])
        assertEquals("T:b", result.translations["u1"])
    }

    @Test
    fun yandexPageRunsChunkWindowsConcurrentlyAndPreservesOrder() = networkTest {
        val texts = (0 until 4).map { index -> "$index".repeat(1_500) }
        var active = 0
        var maxActive = 0
        var arrived = 0
        val bothStarted = CompletableDeferred<Unit>()
        val state = Mutex()
        val engine = MockEngine { request ->
            val waitForBarrier = state.withLock {
                active++
                maxActive = maxOf(maxActive, active)
                arrived++
                if (arrived == 2) bothStarted.complete(Unit)
                arrived <= 2
            }
            if (waitForBarrier) {
                withTimeout(2_000) { bothStarted.await() }
            }
            val sent = request.url.parameters.getAll("text").orEmpty()
            state.withLock { active-- }
            respond(
                content = buildJsonObject {
                    put("code", 200)
                    put(
                        "text",
                        buildJsonArray {
                            for (text in sent) add(JsonPrimitive("T:" + text.first()))
                        },
                    )
                }.toString(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.YANDEX_PA, chunkConcurrency = 2),
        )
        val units = texts.mapIndexed { index, text -> TranslationUnit("u$index", text) }

        val result = manager.translateBatch(request(ProviderId.YANDEX_PA, units)).requireOk()

        assertEquals(2, maxActive)
        assertEquals(4, result.translations.size)
        units.forEach { unit ->
            assertEquals("T:" + unit.text.first(), result.translations[unit.id])
        }
    }

    @Test
    fun yandexPageSplitsUnitAboveSafetyLimit() = networkTest {
        var calls = 0
        val sentLengths = mutableListOf<Int>()
        val engine = MockEngine { request ->
            calls++
            val sent = request.url.parameters.getAll("text").orEmpty()
            sentLengths += sent.sumOf { it.length }
            respond(
                content = buildJsonObject {
                    put("code", 200)
                    put(
                        "text",
                        buildJsonArray {
                            for (text in sent) add(JsonPrimitive("T:" + text.first()))
                        },
                    )
                }.toString(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val manager = manager(
            engine,
            config(ProviderId.YANDEX_PA),
        )

        val result = manager.translateBatch(
            request(ProviderId.YANDEX_PA, "x".repeat(2_001)),
        ).requireOk()

        assertEquals(2, calls)
        assertTrue(sentLengths.all { it <= 2_000 }, "sent lengths: $sentLengths")
        assertEquals("T:xT:x", result.translations["1"])
    }

    @Test
    fun yandexPageMapsPayloadTooLarge() = networkTest {
        val manager = manager(
            MockEngine { respond("""{"code":413}""", HttpStatusCode.PayloadTooLarge) },
            config(ProviderId.YANDEX_PA),
        )

        val error = manager.translateBatch(
            request(ProviderId.YANDEX_PA, "Hello"),
        ).requireErr()

        assertIs<TranslationError.InvalidRequest>(error)
    }

    @Test
    fun yandexRejectedRequestIsNotAuth() = networkTest {
        val manager = manager(
            MockEngine {
                respond(
                    content = """{"code":405,"message":"Method not allowed"}""",
                    status = HttpStatusCode.Forbidden,
                )
            },
            config(ProviderId.YANDEX_PA),
        )

        val error = manager.translateBatch(
            request(ProviderId.YANDEX_PA, "Hello"),
        ).requireErr()

        assertIs<TranslationError.InvalidResponse>(error)
    }

    private fun networkTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    /**
     * Releases the first [expected] in-flight requests together and records the
     * peak. A sequential implementation blocks on the barrier and times out.
     */
    private class InFlightProbe(private val expected: Int) {
        private val state = Mutex()
        private val ready = CompletableDeferred<Unit>()
        private var active = 0
        private var arrived = 0
        private var peak = 0

        val maxActive: Int get() = peak

        suspend fun <T> track(block: suspend () -> T): T {
            val shouldWait = state.withLock {
                active++
                peak = maxOf(peak, active)
                arrived++
                if (arrived == expected) ready.complete(Unit)
                arrived <= expected
            }
            try {
                if (shouldWait) withTimeout(2_000) { ready.await() }
                return block()
            } finally {
                state.withLock { active-- }
            }
        }
    }

    private fun manager(engine: MockEngine, vararg configs: TranslationProviderConfig) =
        TranslationManagerImpl(
            client = HttpClient(engine),
            configs = configs.toList(),
        )

    private fun config(
        provider: ProviderId,
        baseUrl: String? = null,
        model: String? = null,
        maxOutputTokens: Int? = null,
        maxCharsPerRequest: Int? = null,
        folderId: String? = null,
        useJsonSchema: Boolean = false,
        chunkConcurrency: Int = 1,
        keys: List<String> = listOf("test-key"),
    ): TranslationProviderConfig = TranslationProviderConfig(
        provider = provider,
        apiKeys = keys,
        model = model,
        baseUrl = baseUrl,
        maxOutputTokens = maxOutputTokens,
        maxAttempts = 1,
        maxCharsPerRequest = maxCharsPerRequest,
        folderId = folderId,
        useJsonSchema = useJsonSchema,
        chunkConcurrency = chunkConcurrency,
    )

    private suspend fun jsonBody(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    private suspend fun formBody(request: HttpRequestData): Parameters =
        parseQueryString(request.body.toByteArray().decodeToString())

    private fun userContent(root: JsonObject): String =
        root.getValue("messages").jsonArray[1]
            .jsonObject.getValue("content").jsonPrimitive.content

    private fun anthropicInput(root: JsonObject): String =
        root.getValue("messages").jsonArray.single()
            .jsonObject.getValue("content").jsonPrimitive.content

    private fun idsIn(input: String): List<String> =
        Json.parseToJsonElement(input).jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
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

    private fun chatResponse(inner: String, finishReason: String = "stop"): String =
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put("content", JsonPrimitive(inner))
                                },
                            )
                            put("finish_reason", finishReason)
                        },
                    )
                },
            )
        }.toString()

    private fun geminiResponse(inner: String): String =
        """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(inner)}}]},"finishReason":"STOP"}]}"""

    private fun anthropicResponse(inner: String, stopReason: String = "end_turn"): String =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", inner)
                        },
                    )
                },
            )
            put("stop_reason", stopReason)
        }.toString()

    private fun request(
        provider: ProviderId,
        units: List<TranslationUnit>,
        source: LanguageCode? = LanguageCode.parse("en"),
        target: LanguageCode = LanguageCode.parse("es"),
    ): BatchTranslationRequest = BatchTranslationRequest(
        bookId = 1,
        chapterKey = "chapter-1",
        units = units,
        source = source,
        target = target,
        provider = provider,
    )

    private fun request(
        provider: ProviderId,
        text: String,
        source: LanguageCode? = LanguageCode.parse("en"),
    ): BatchTranslationRequest = request(provider, listOf(TranslationUnit("1", text)), source)

    private fun <T> Outcome<T>.requireOk(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> fail("expected Ok, got $error")
    }

    private fun <T> Outcome<T>.requireErr(): MiruError = when (this) {
        is Outcome.Ok -> fail("expected Err, got $value")
        is Outcome.Err -> error
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
