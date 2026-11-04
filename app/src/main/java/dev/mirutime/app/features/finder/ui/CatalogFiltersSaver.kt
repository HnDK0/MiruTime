package dev.mirutime.app.features.finder.ui

import androidx.compose.runtime.saveable.Saver
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the applied catalogue filters across the book round-trip, so
 * returning from a book keeps the filtered result set instead of reloading the
 * default listing (FR-077). A JSON string is Bundle-safe for rememberSaveable.
 */
internal val CatalogFiltersSaver: Saver<CatalogFilters, String> = Saver(
    save = { it.encodeCatalogFilters() },
    restore = { runCatching { decodeCatalogFilters(it) }.getOrNull() },
)

internal fun CatalogFilters.encodeCatalogFilters(): String = JSONObject()
    .put("q", query)
    .put("sort", sort.name)
    .put("desc", descending)
    .put("genres", JSONArray(genres.toList()))
    .put("ongoing", ongoingOnly)
    .put(
        "plugin",
        JSONObject().apply {
            pluginValues.forEach { (key, values) -> put(key, JSONArray(values)) }
        },
    )
    .toString()

internal fun decodeCatalogFilters(raw: String): CatalogFilters {
    val json = JSONObject(raw)
    val genres = json.optJSONArray("genres")?.let { array ->
        buildSet {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }.orEmpty()
    val pluginValues = json.optJSONObject("plugin")?.let { objectValue ->
        buildMap {
            objectValue.keys().forEach { key ->
                val values = objectValue.optJSONArray(key)?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }.orEmpty()
                put(key, values)
            }
        }
    }.orEmpty()
    return CatalogFilters(
        query = json.optString("q", ""),
        sort = runCatching {
            CatalogSort.valueOf(json.optString("sort", CatalogSort.Popular.name))
        }.getOrDefault(CatalogSort.Popular),
        descending = json.optBoolean("desc", true),
        genres = genres,
        ongoingOnly = json.optBoolean("ongoing", false),
        pluginValues = pluginValues,
    )
}
