package dev.mirutime.translator.internal

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val DEFAULT_PROMPT: String =
    "Translate from {source_language} to {target_language}. " +
        "The input is a JSON array of objects with an id and a text field. " +
        "Translate every item independently. " +
        "Return only JSON with a translations array; each item must contain the exact id copied " +
        "from the input and the translated text. " +
        "Do not merge, drop, renumber, or reorder items."

internal fun renderPrompt(
    prompt: PromptSpec?,
    source: LanguageCode?,
    target: LanguageCode,
): String {
    val resolvedSource = source?.value
        ?: throw TranslationFailure(
            TranslationError.InvalidRequest(null, "source language is required for a prompt"),
        )
    val template = prompt?.text?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT
    return template
        .replace("{source_language}", resolvedSource)
        .replace("{target_language}", target.value)
}

internal fun jsonInput(units: List<TranslationUnit>): String = buildJsonArray {
    for (unit in units) {
        add(
            buildJsonObject {
                put("id", unit.id)
                put("text", unit.text)
            },
        )
    }
}.toString()

internal fun stableHash(text: String): String {
    var hash = 0x811c9dc5u
    for (ch in text) {
        hash = (hash xor ch.code.toUInt()) * 0x01000193u
    }
    return hash.toString(16)
}

internal fun parseTranslations(
    raw: String,
    units: List<TranslationUnit>,
    provider: ProviderId,
): Map<String, String> {
    val json = try {
        Json.parseToJsonElement(raw)
    } catch (_: Throwable) {
        return parseNumberedTranslations(raw, units, provider)
    }
    return parseJsonTranslations(json, units, provider)
}

private fun parseJsonTranslations(
    root: kotlinx.serialization.json.JsonElement,
    units: List<TranslationUnit>,
    provider: ProviderId,
): Map<String, String> {
    val array = when (root) {
        is JsonObject -> root["translations"] as? JsonArray
        is JsonArray -> root
        else -> null
    } ?: throw TranslationFailure(
        TranslationError.InvalidResponse(provider, "missing translations array"),
    )

    val parsed = array.associate { item ->
        val obj = item as? JsonObject
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(provider, "translation item is not an object"),
            )
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(provider, "translation item without id"),
            )
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(provider, "translation item without text"),
            )
        id to text
    }

    val expected = units.map { it.id }.toSet()
    if (parsed.keys != expected) {
        throw TranslationFailure(
            TranslationError.Truncated(provider, expected.size, parsed.size),
        )
    }
    return parsed
}

private fun parseNumberedTranslations(
    raw: String,
    units: List<TranslationUnit>,
    provider: ProviderId,
): Map<String, String> {
    val regex = Regex("""^\s*(?:\*\*)?(\d+)(?:\.|\)|:)\s*(?:\*\*)?\s*(.*)$""")
    val byIndex = linkedMapOf<Int, String>()
    var current: Int? = null
    for (line in raw.lineSequence()) {
        val match = regex.find(line)
        if (match != null) {
            val index = match.groupValues[1].toIntOrNull()
            current = index?.takeIf { it in 1..units.size }
            if (current != null) {
                byIndex[current] = match.groupValues[2].trim()
            }
            continue
        }

        val value = line.trim()
        val index = current
        if (index != null && value.isNotEmpty()) {
            byIndex[index] = listOf(byIndex.getValue(index), value).joinToString("\n")
        }
    }

    if (byIndex.size != units.size) {
        throw TranslationFailure(
            TranslationError.Truncated(provider, units.size, byIndex.size),
        )
    }
    return units.mapIndexed { index, unit -> unit.id to byIndex.getValue(index + 1) }.toMap()
}
