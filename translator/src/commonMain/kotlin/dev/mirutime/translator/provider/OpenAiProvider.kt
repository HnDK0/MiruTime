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
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

internal class OpenAiProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.OPENAI

    private val model = config.model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    private val endpoint = (config.baseUrl?.trimEnd('/') ?: BASE_URL) + "/v1/responses"
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
                    headers = mapOf(
                        HttpHeaders.Authorization to "Bearer ${key.orEmpty()}",
                    ),
                )
            }
        },
        parse = { response ->
            parseTranslations(parseOpenAi(response.bodyAsText(), group.size), group, id)
        },
    )

    private fun body(prompt: String, input: String): String = buildJsonObject {
        put("model", model)
        put("instructions", prompt)
        put("input", input)
        put("max_output_tokens", maxOutputTokens)
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "translations")
                put("strict", true)
                put("schema", responseSchema)
            }
        }
    }.toString()

    private fun parseOpenAi(body: String, expected: Int): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected OpenAI response"),
            )

        if (root["status"]?.jsonPrimitive?.contentOrNull == "failed") {
            val error = root["error"] as? JsonObject
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: error?.get("code")?.jsonPrimitive?.contentOrNull
                ?: "OpenAI response failed"
            throw TranslationFailure(TranslationError.InvalidResponse(id, message))
        }

        val incompleteReason = root["incomplete_details"]
            ?.jsonObject?.get("reason")
            ?.jsonPrimitive?.contentOrNull
        when (incompleteReason) {
            "content_filter" -> throw TranslationFailure(
                TranslationError.ContentBlocked(id, incompleteReason),
            )
            "max_output_tokens" -> throw TranslationFailure(
                TranslationError.Truncated(id, expected, 0),
            )
        }

        val output = root["output"]?.jsonArray
        val outputText = buildString {
            if (output == null) return@buildString
            for (item in output) {
                val content = (item as? JsonObject)?.get("content") as? JsonArray ?: continue
                for (part in content) {
                    val obj = part as? JsonObject ?: continue
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "refusal" -> throw TranslationFailure(
                            TranslationError.ContentBlocked(id, "refusal"),
                        )
                        "output_text" -> append(
                            obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    }
                }
            }
        }.trim()

        root["output_text"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return outputText.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "OpenAI returned empty text"),
            )
    }

    private companion object {
        const val BASE_URL = "https://api.openai.com"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_MAX_OUTPUT = 16_384

        val responseSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("translations") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("id"))
                            add(JsonPrimitive("text"))
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("translations"))
            }
            put("additionalProperties", false)
        }
    }
}
