package dev.mirutime.app.features.book.data

import org.json.JSONArray

// BookEntity stores alt titles as a JSON string array. Parsing stays in the
// data layer; the display rule lives in the domain mapper.
internal fun parseAltTitles(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
