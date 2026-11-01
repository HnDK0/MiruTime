package dev.mirutime.app.features.finder.domain

import dev.mirutime.core.log.MiruLog

// A plugin-declared catalogue filter (NoveLA getFilterList()).
//
// The host core flattens Lua tables into List<Map<String, String>> because its
// public contract has no nested types: scalars stay bare keys and option lists
// become "options.count" + "options.<i>.value"/"options.<i>.label"
// (LuaPluginSource.flattenInto). This model is the typed view of that shape,
// shared by finder filters now and plugin settings later.
enum class PluginFilterType(val wire: String) {
    Sort("sort"),
    Select("select"),
    Checkbox("checkbox"),
    Tristate("tristate"),
    Switch("switch"),
    Text("text"),
    TagInput("tag_input"),
    ;

    companion object {
        fun fromWire(raw: String?): PluginFilterType? =
            entries.firstOrNull { it.wire == raw }
    }
}

data class PluginFilterOption(val value: String, val label: String)

data class PluginFilter(
    val key: String,
    val type: PluginFilterType,
    val label: String,
    val options: List<PluginFilterOption> = emptyList(),
    // Switch: "true"/"false". Sort/select/text: the plugin's chosen default.
    val defaultValue: String = "",
    val defaultAscending: Boolean = false,
    val multiselect: Boolean = true,
    val allowCustom: Boolean = true,
) {
    val isSingleValue: Boolean
        get() = type == PluginFilterType.Sort ||
            type == PluginFilterType.Select ||
            type == PluginFilterType.Switch ||
            type == PluginFilterType.Text

    // The exact Lua keys this filter writes, without values. Kept in one
    // place so preset validation and the request builder cannot drift.
    val requestKeys: Set<String>
        get() = buildSet {
            add(key)
            when (type) {
                PluginFilterType.Sort -> add("${key}_ascending")
                PluginFilterType.Checkbox, PluginFilterType.TagInput -> add("${key}_included")
                PluginFilterType.Tristate -> {
                    add("${key}_included")
                    add("${key}_excluded")
                }
                PluginFilterType.Select, PluginFilterType.Switch, PluginFilterType.Text -> Unit
            }
        }
}

// Unknown types are dropped after a warning: a new plugin type must not crash
// the catalogue or silently render as a different widget. Missing keys and
// option-less choice filters are dropped like NoveLA's parser.
fun parsePluginFilters(raw: List<Map<String, String>>): List<PluginFilter> =
    raw.mapNotNull(::parsePluginFilter)

private fun parsePluginFilter(map: Map<String, String>): PluginFilter? {
    val key = map["key"]?.trim().orEmpty()
    if (key.isEmpty()) return null
    val rawType = map["type"]?.trim()
    val type = if (rawType.isNullOrEmpty()) {
        // NoveLA defaults a missing type to select.
        PluginFilterType.Select
    } else {
        PluginFilterType.fromWire(rawType) ?: run {
            MiruLog.w("finder", "event=filter_schema_unknown_type key=$key type=$rawType")
            return null
        }
    }
    val label = map["label"]?.takeIf { it.isNotBlank() } ?: key
    val options = parsePluginFilterOptions(map)
    if (type != PluginFilterType.TagInput &&
        type != PluginFilterType.Switch &&
        type != PluginFilterType.Text &&
        options.isEmpty()
    ) {
        return null
    }
    // `current` is the settings-schema spelling of `defaultValue`; reading
    // both keeps this parser reusable for getSettingsSchema() later.
    val rawDefault = map["defaultValue"] ?: map["current"]
    val defaultValue = when (type) {
        PluginFilterType.Sort -> rawDefault?.takeIf { it.isNotBlank() }
            ?: options.firstOrNull()?.value
            ?: ""
        PluginFilterType.Switch -> rawDefault.toBooleanWire()
        PluginFilterType.Select,
        PluginFilterType.Text,
        PluginFilterType.Checkbox,
        PluginFilterType.Tristate,
        PluginFilterType.TagInput,
        -> rawDefault.orEmpty()
    }
    return PluginFilter(
        key = key,
        type = type,
        label = label,
        options = options,
        defaultValue = defaultValue,
        defaultAscending = map["defaultAscending"]?.toBooleanStrictOrNull() ?: false,
        multiselect = map["multiselect"]?.toBooleanStrictOrNull() ?: true,
        allowCustom = map["allowCustom"]?.toBooleanStrictOrNull() ?: true,
    )
}

private fun parsePluginFilterOptions(map: Map<String, String>): List<PluginFilterOption> {
    val count = map["options.count"]?.toIntOrNull()?.coerceAtLeast(0) ?: return emptyList()
    return (0 until count).mapNotNull { index ->
        // NoveLA keeps a blank option value (LuaFilterSupport.kt:310-313):
        // plugins encode "all"/"any" as value = "" with a real label. Dropping
        // it removes the only way back to the default and leaves the selected
        // row blank. A fully empty option (no value and no label) is noise.
        val rawValue = map["options.$index.value"]
        val rawLabel = map["options.$index.label"]
        if (rawValue == null && rawLabel.isNullOrBlank()) return@mapNotNull null
        val value = rawValue.orEmpty()
        val label = rawLabel?.takeIf { it.isNotBlank() } ?: value
        if (value.isBlank() && label.isBlank()) return@mapNotNull null
        PluginFilterOption(value = value, label = label)
    }
}

private fun String?.toBooleanWire(): String =
    when (this?.trim()?.lowercase()) {
        "true" -> "true"
        "false" -> "false"
        else -> "false"
    }

// Defaults are always sent to the plugin (NoveLA ActiveFilters includes the
// widget defaults); only values that differ from a default live in
// CatalogFilters.pluginValues so the badge stays honest.
fun defaultPluginValues(schema: List<PluginFilter>): Map<String, List<String>> = buildMap {
    schema.forEach { filter ->
        when (filter.type) {
            PluginFilterType.Sort -> {
                if (filter.defaultValue.isNotBlank()) {
                    put(filter.key, listOf(filter.defaultValue))
                }
                put("${filter.key}_ascending", listOf(filter.defaultAscending.toString()))
            }
            PluginFilterType.Select,
            PluginFilterType.Text,
            -> if (filter.defaultValue.isNotBlank()) {
                put(filter.key, listOf(filter.defaultValue))
            }
            PluginFilterType.Switch -> put(
                filter.key,
                listOf(filter.defaultValue.toBooleanWire()),
            )
            PluginFilterType.Checkbox,
            PluginFilterType.Tristate,
            PluginFilterType.TagInput,
            -> Unit
        }
    }
}

// Exact request map: defaults first, active values override them, empty lists
// are omitted (an absent key means "not set" to the Lua side).
fun pluginFilterMap(
    filters: CatalogFilters,
    schema: List<PluginFilter>,
): Map<String, List<String>> = (defaultPluginValues(schema) + filters.pluginValues)
    .filterValues { it.isNotEmpty() }

// Reserved host -> plugin key: a plugin that advertises search_filters reads
// the query from catalogFiltered's filter map. It is not a public filter and
// never appears in schemas, presets or the active-filter badge.
internal const val SEARCH_FILTER_QUERY_KEY = "_query"

// Exact request map for a catalogue page. Query search has no filter argument
// in the core contract unless the plugin claims `search_filters`; only then
// the query is composed with the filters under the reserved `_query` key.
// Returning null keeps callers on the plain search path, so filters are never
// silently promised to a plugin that cannot consume them.
//
// NoveLA parity: the unfiltered catalog is getCatalogList. Only a filter the
// user actually changed (filters.pluginValues) — or a capable plugin's
// `_query` — switches to getCatalogFiltered; schema defaults alone must not
// silently turn the first page into a search-adv request.
internal fun pluginFilterRequest(
    filters: CatalogFilters,
    schema: List<PluginFilter>,
    supportsSearchFilters: Boolean = false,
): Map<String, List<String>>? {
    if (filters.query.isNotBlank()) {
        if (!supportsSearchFilters || schema.isEmpty()) return null
        return (
            defaultPluginValues(schema) +
                filters.pluginValues +
                (SEARCH_FILTER_QUERY_KEY to listOf(filters.query))
            ).filterValues { it.isNotEmpty() }
    }
    return filters.pluginValues.filterValues { it.isNotEmpty() }
}

// The exact source call for one catalogue page. Pure so tests can pin the
// capability rule without a PluginGraph:
//  - capable plugin + query (+ schema) -> ONE catalogFiltered with `_query`;
//  - unsupported query -> search (never silently promises filters);
//  - no query, filters changed -> catalogFiltered;
//  - otherwise -> catalog.
sealed interface CatalogRequest {
    data class Search(val query: String) : CatalogRequest
    data class Filtered(val filters: Map<String, kotlin.collections.List<String>>) : CatalogRequest
    data object List : CatalogRequest
}

internal fun pluginCatalogRequest(
    filters: CatalogFilters,
    schema: List<PluginFilter>,
    supportsSearchFilters: Boolean,
): CatalogRequest {
    val request = pluginFilterRequest(filters, schema, supportsSearchFilters)
    return when {
        request != null && request.isNotEmpty() -> CatalogRequest.Filtered(request)
        filters.query.isNotBlank() -> CatalogRequest.Search(filters.query)
        else -> CatalogRequest.List
    }
}

// One filter's values in final Lua keys. UI widgets later call this instead of
// re-implementing suffix rules.
fun PluginFilter.toLuaValues(
    value: String = defaultValue,
    included: List<String> = emptyList(),
    excluded: List<String> = emptyList(),
    ascending: Boolean = defaultAscending,
): Map<String, List<String>> = buildMap {
    when (type) {
        PluginFilterType.Sort -> {
            if (value.isNotBlank()) put(key, listOf(value))
            put("${key}_ascending", listOf(ascending.toString()))
        }
        PluginFilterType.Select -> if (value.isNotBlank()) put(key, listOf(value))
        PluginFilterType.Switch -> put(key, listOf(value.toBooleanWire()))
        PluginFilterType.Text -> if (value.isNotBlank()) put(key, listOf(value))
        PluginFilterType.Checkbox,
        PluginFilterType.TagInput,
        -> if (included.isNotEmpty()) put("${key}_included", included)
        PluginFilterType.Tristate -> {
            if (included.isNotEmpty()) put("${key}_included", included)
            if (excluded.isNotEmpty()) put("${key}_excluded", excluded)
        }
    }
}

// A preset may outlive the plugin schema (plugin update removes/renames a
// filter). Keep only keys the current schema declares; legacy genre/status
// values are dropped for schema sources because they were synthetic promises.
fun CatalogFilters.restrictToSchema(schema: List<PluginFilter>): CatalogFilters {
    if (schema.isEmpty()) return this
    val keys = schema.flatMapTo(mutableSetOf()) { it.requestKeys }
    return copy(
        genres = emptySet(),
        ongoingOnly = false,
        pluginValues = pluginValues
            .filterKeys { it in keys }
            .filterValues { it.isNotEmpty() },
    )
}
