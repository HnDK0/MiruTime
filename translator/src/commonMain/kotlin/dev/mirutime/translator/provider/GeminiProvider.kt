package dev.mirutime.translator.provider

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.TranslationUnit
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.LLM_UNIT_OVERHEAD
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.jsonInput
import dev.mirutime.translator.internal.llmMaxChars
import dev.mirutime.translator.internal.parseTranslations
import dev.mirutime.translator.internal.renderPrompt
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

internal class GeminiProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.GEMINI

    private val model = config.model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    private val endpoint = (config.baseUrl?.trimEnd('/') ?: BASE_URL) +
        "/v1beta/models/$model:generateContent"
    private val timeout = config.timeout
    private val maxOutputTokens = config.maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT
    private val maxCharsPerRequest = config.maxCharsPerRequest
    private val concurrency = config.chunkConcurrency.coerceIn(1, 32)
    private val keys = KeyRotator(config.apiKeys, timeSource = timeSource)
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val prompt = renderPrompt(request.prompt, request.source, request.target)
        val maxChars = llmMaxChars(
            configured = maxCharsPerRequest,
            maxOutputTokens = maxOutputTokens,
            defaultMaxOutputTokens = DEFAULT_MAX_OUTPUT,
            target = request.target,
        )
        val plan = splitToBudget(request.units, maxChars, id)
        val groups = chunkUnitGroups(plan.units, maxChars, overheadPerUnit = LLM_UNIT_OVERHEAD)
        val results = if (concurrency == 1) {
            groups.map { group -> translateGroup(group, prompt) }
        } else {
            boundedMap(groups, concurrency) { group -> translateGroup(group, prompt) }
        }
        val translations = linkedMapOf<String, String>()
        for (parsed in results) {
            translations.putAll(parsed)
        }

        return ProviderResult(plan.restore(translations), model)
    }

    private suspend fun translateGroup(
        group: List<TranslationUnit>,
        prompt: String,
    ): Map<String, String> = executor.execute(
        request = { key ->
            withProviderTimeout(id, timeout) {
                http.post(
                    url = endpoint,
                    body = body(prompt, jsonInput(group)),
                    contentType = ContentType.Application.Json.toString(),
                    headers = mapOf("x-goog-api-key" to key.orEmpty()),
                )
            }
        },
        parse = { response ->
            parseTranslations(parseGemini(response.bodyAsText(), group.size), group, id)
        },
    )

    private fun body(prompt: String, input: String): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", prompt) })
            }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", input) })
                    }
                },
            )
        }
        putJsonArray("safetySettings") {
            for (category in SAFETY_CATEGORIES) {
                add(
                    buildJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    },
                )
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.15)
            put("topP", 0.9)
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchema)
            put("maxOutputTokens", maxOutputTokens)
        }
    }.toString()

    private fun parseGemini(body: String, expected: Int): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected Gemini response"),
            )

        val blockReason = root["promptFeedback"]
            ?.jsonObject?.get("blockReason")
            ?.jsonPrimitive?.contentOrNull
        if (!blockReason.isNullOrBlank()) {
            throw TranslationFailure(TranslationError.ContentBlocked(id, blockReason))
        }

        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "Gemini returned no candidates"),
            )
        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        if (finishReason == "MAX_TOKENS") {
            throw TranslationFailure(TranslationError.Truncated(id, expected, 0))
        }
        if (finishReason in BLOCKED_REASONS) {
            throw TranslationFailure(TranslationError.ContentBlocked(id, finishReason))
        }

        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "Gemini returned no content parts"),
            )
        val text = parts.joinToString("") { part ->
            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.trim()

        return text.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "Gemini returned empty text"),
            )
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        const val DEFAULT_MAX_OUTPUT = 65_536
        val BLOCKED_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        )

        val responseSchema: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("translations") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "STRING") }
                            putJsonObject("text") { put("type", "STRING") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("id"))
                            add(JsonPrimitive("text"))
                        }
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("translations"))
            }
        }
    }
}
