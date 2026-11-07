package dev.mirutime.app.features.book.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// BookEntity stores alt titles as a JSON string array. Parsing stays in the
// data layer; the display rule lives in the domain mapper.
internal fun parseAltTitles(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
