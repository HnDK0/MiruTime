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
import dev.mirutime.translator.internal.translationsJsonSchema
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

internal class AnthropicProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.ANTHROPIC

    private val model = config.model?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    private val endpoint = (config.baseUrl?.trimEnd('/') ?: BASE_URL) + "/v1/messages"
    private val timeout = config.timeout
    private val maxOutputTokens = config.maxOutputTokens
    private val maxTokens = maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
    private val maxCharsPerRequest = config.maxCharsPerRequest
    private val concurrency = config.chunkConcurrency.coerceIn(1, 32)
    private val keys = KeyRotator(config.apiKeys, timeSource = timeSource)
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val prompt = renderPrompt(request.prompt, request.source, request.target)
        val maxChars = llmMaxChars(
            configured = maxCharsPerRequest,
            maxOutputTokens = maxTokens,
            defaultMaxOutputTokens = DEFAULT_MAX_TOKENS,
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
                        "x-api-key" to key.orEmpty(),
                        "anthropic-version" to ANTHROPIC_VERSION,
                    ),
                )
            }
        },
        parse = { response ->
            parseTranslations(parseContent(response.bodyAsText(), group.size), group, id)
        },
    )

    private fun body(prompt: String, input: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("system", prompt)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", input)
                },
            )
        }
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", translationsJsonSchema)
            }
        }
    }.toString()

    private fun parseContent(body: String, expected: Int): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected Anthropic response"),
            )

        when (root["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "max_tokens", "model_context_window_exceeded" -> throw TranslationFailure(
                TranslationError.Truncated(id, expected, 0),
            )
            "refusal" -> throw TranslationFailure(
                TranslationError.ContentBlocked(id, "refusal"),
            )
        }

        val content = root["content"]?.jsonArray
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "Anthropic returned no content"),
            )
        val text = buildString {
            for (part in content) {
                val obj = part as? JsonObject ?: continue
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "refusal" -> throw TranslationFailure(
                        TranslationError.ContentBlocked(id, "refusal"),
                    )
                }
            }
        }.trim()

        return text.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "Anthropic returned empty text"),
            )
    }

    private companion object {
        const val BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_MAX_TOKENS = 65_536
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
