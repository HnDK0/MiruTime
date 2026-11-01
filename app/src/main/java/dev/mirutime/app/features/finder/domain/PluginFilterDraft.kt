package dev.mirutime.app.features.finder.domain

// Draft state of plugin-declared catalogue filters. Compose widgets edit this
// model; [toPluginValues] turns it back into the exact Lua keys, and only for
// values that differ from the schema defaults, so the active-filter badge and
// saved presets stay honest while [defaultPluginValues] still sends every
// default to the plugin.
enum class TriState { Neutral, Include, Exclude }

sealed interface PluginFilterValue {
    data class Sort(val value: String, val ascending: Boolean) : PluginFilterValue
    data class Select(val value: String) : PluginFilterValue
    data class Checkbox(val included: Set<String>) : PluginFilterValue
    data class Tristate(val states: Map<String, TriState>) : PluginFilterValue
    data class Toggle(val enabled: Boolean) : PluginFilterValue
    data class TextInput(val value: String) : PluginFilterValue
    data class Tags(val values: List<String>) : PluginFilterValue
}

// One value per filter, initialised from the plugin schema. Reset restores this
// map, so "defaults" never drift from a hand-written UI list.
fun pluginFilterDefaults(schema: List<PluginFilter>): Map<String, PluginFilterValue> =
    buildMap {
        schema.forEach { filter ->
            put(
                filter.key,
                when (filter.type) {
                    PluginFilterType.Sort -> PluginFilterValue.Sort(
                        value = filter.defaultValue,
                        ascending = filter.defaultAscending,
                    )
                    PluginFilterType.Select -> PluginFilterValue.Select(filter.defaultValue)
                    PluginFilterType.Checkbox -> PluginFilterValue.Checkbox(emptySet())
                    PluginFilterType.Tristate -> PluginFilterValue.Tristate(emptyMap())
                    PluginFilterType.Switch -> PluginFilterValue.Toggle(
                        filter.defaultValue.booleanWire(),
                    )
                    PluginFilterType.Text -> PluginFilterValue.TextInput(filter.defaultValue)
                    PluginFilterType.TagInput -> PluginFilterValue.Tags(emptyList())
                },
            )
        }
    }

// Applied filters -> draft. Values absent from [CatalogFilters.pluginValues]
// fall back to the schema default, so a preset can be loaded into the widgets
// even when it only stored the non-default keys.
fun CatalogFilters.toPluginFilterDraft(
    schema: List<PluginFilter>,
): Map<String, PluginFilterValue> {
    val draft = pluginFilterDefaults(schema).toMutableMap()
    schema.forEach { filter ->
        val first = pluginValues[filter.key]?.firstOrNull()
        draft[filter.key] = when (filter.type) {
            PluginFilterType.Sort -> PluginFilterValue.Sort(
                value = first?.takeIf { it.isNotBlank() } ?: filter.defaultValue,
                ascending = pluginValues["${filter.key}_ascending"]
                    ?.firstOrNull()
                    ?.toBooleanStrictOrNull()
                    ?: filter.defaultAscending,
            )
            PluginFilterType.Select -> PluginFilterValue.Select(
                first?.takeIf { it.isNotBlank() } ?: filter.defaultValue,
            )
            PluginFilterType.Checkbox -> PluginFilterValue.Checkbox(
                pluginValues["${filter.key}_included"].orEmpty().toSet(),
            )
            PluginFilterType.Tristate -> {
                val included = pluginValues["${filter.key}_included"].orEmpty()
                val excluded = pluginValues["${filter.key}_excluded"].orEmpty()
                PluginFilterValue.Tristate(
                    buildMap {
                        included.forEach { put(it, TriState.Include) }
                        excluded.forEach { put(it, TriState.Exclude) }
                    },
                )
            }
            PluginFilterType.Switch -> PluginFilterValue.Toggle(
                first?.toBooleanStrictOrNull() ?: filter.defaultValue.booleanWire(),
            )
            PluginFilterType.Text -> PluginFilterValue.TextInput(
                first ?: filter.defaultValue,
            )
            PluginFilterType.TagInput -> PluginFilterValue.Tags(
                pluginValues["${filter.key}_included"].orEmpty(),
            )
        }
    }
    return draft
}

fun PluginFilter.toLuaValues(state: PluginFilterValue): Map<String, List<String>> =
    when (state) {
        is PluginFilterValue.Sort -> toLuaValues(value = state.value, ascending = state.ascending)
        is PluginFilterValue.Select -> toLuaValues(value = state.value)
        is PluginFilterValue.Checkbox -> toLuaValues(included = state.included.sorted())
        is PluginFilterValue.Tristate -> toLuaValues(
            included = state.states.filterValues { it == TriState.Include }.keys.sorted(),
            excluded = state.states.filterValues { it == TriState.Exclude }.keys.sorted(),
        )
        is PluginFilterValue.Toggle -> toLuaValues(
            value = if (state.enabled) "true" else "false",
        )
        is PluginFilterValue.TextInput -> toLuaValues(value = state.value)
        is PluginFilterValue.Tags -> toLuaValues(included = state.values.distinct())
    }

// Draft -> final Lua keys, minus anything still equal to the schema default.
// The request builder merges the defaults back in, so dropping them here only
// keeps the badge/preset honest.
fun Map<String, PluginFilterValue>.toPluginValues(
    schema: List<PluginFilter>,
): Map<String, List<String>> {
    val defaults = pluginFilterDefaults(schema)
    return buildMap {
        schema.forEach { filter ->
            val state = this@toPluginValues[filter.key] ?: return@forEach
            val default = defaults[filter.key] ?: return@forEach
            val currentValues = filter.toLuaValues(state)
            val defaultValues = filter.toLuaValues(default)
            currentValues.forEach { (key, values) ->
                if (values.isNotEmpty() && defaultValues[key] != values) {
                    put(key, values)
                }
            }
        }
    }
}

// Apply the plugin draft to a catalogue request. The fixed app sort/genres/
// status are removed for plugin sources: they were never a plugin promise.
// An empty schema (still loading or plugin has none) keeps the applied plugin
// values untouched instead of silently wiping a preset.
fun CatalogFilters.withPluginDraft(
    schema: List<PluginFilter>,
    draft: Map<String, PluginFilterValue>,
): CatalogFilters {
    val clean = copy(
        sort = CatalogSort.Popular,
        descending = true,
        genres = emptySet(),
        ongoingOnly = false,
    )
    if (schema.isEmpty()) return clean
    return clean.copy(pluginValues = draft.toPluginValues(schema))
}

// One changed plugin filter counts once, regardless of how many Lua keys it
// writes (a sort is still a single choice for the user).
fun CatalogFilters.pluginActiveFilterCount(schema: List<PluginFilter>): Int =
    schema.count { filter ->
        filter.requestKeys.any { pluginValues[it].orEmpty().isNotEmpty() }
    }

// --- Widget transitions (pure, so the sheet's state machine is testable) ---

fun Map<String, PluginFilterValue>.withSort(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> {
    val ascending = (this[filter.key] as? PluginFilterValue.Sort)?.ascending
        ?: filter.defaultAscending
    return this + (filter.key to PluginFilterValue.Sort(value, ascending))
}

fun Map<String, PluginFilterValue>.toggleSortDirection(
    filter: PluginFilter,
): Map<String, PluginFilterValue> {
    val current = this[filter.key] as? PluginFilterValue.Sort ?: return this
    return this + (filter.key to current.copy(ascending = !current.ascending))
}

fun Map<String, PluginFilterValue>.withSelect(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> =
    this + (filter.key to PluginFilterValue.Select(value))

fun Map<String, PluginFilterValue>.toggleCheckbox(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> {
    val current = (this[filter.key] as? PluginFilterValue.Checkbox)?.included.orEmpty()
    val next = when {
        // multiselect=false is a one-of-N choice: a new value replaces the old.
        !filter.multiselect -> if (value in current) emptySet() else setOf(value)
        value in current -> current - value
        else -> current + value
    }
    return this + (filter.key to PluginFilterValue.Checkbox(next))
}

fun Map<String, PluginFilterValue>.cycleTristate(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> {
    val current = (this[filter.key] as? PluginFilterValue.Tristate)
        ?.states
        .orEmpty()
        .toMutableMap()
    when (current[value]) {
        null, TriState.Neutral -> current[value] = TriState.Include
        TriState.Include -> current[value] = TriState.Exclude
        TriState.Exclude -> current.remove(value)
    }
    return this + (filter.key to PluginFilterValue.Tristate(current))
}

fun Map<String, PluginFilterValue>.withSwitch(
    filter: PluginFilter,
    enabled: Boolean,
): Map<String, PluginFilterValue> =
    this + (filter.key to PluginFilterValue.Toggle(enabled))

fun Map<String, PluginFilterValue>.withText(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> =
    this + (filter.key to PluginFilterValue.TextInput(value))

fun Map<String, PluginFilterValue>.toggleTag(
    filter: PluginFilter,
    value: String,
): Map<String, PluginFilterValue> {
    val clean = value.trim()
    if (clean.isEmpty()) return this
    val current = (this[filter.key] as? PluginFilterValue.Tags)?.values.orEmpty()
    val next = if (clean in current) current - clean else current + clean
    return this + (filter.key to PluginFilterValue.Tags(next))
}

private fun String.booleanWire(): Boolean = trim().lowercase() == "true"
