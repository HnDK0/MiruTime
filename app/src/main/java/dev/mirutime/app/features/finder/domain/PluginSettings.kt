package dev.mirutime.app.features.finder.domain

import dev.mirutime.core.log.MiruLog

// One entry of a plugin's getSettingsSchema(), before it is bound to the
// preference store. `current` is whatever the plugin reported (empty when it
// reported none): plugins either compute it from get_preference or ship a
// static default, so the stored value is applied on top of it by the loader.
data class PluginSettingSpec(
    val key: String,
    val label: String,
    val options: List<PluginSettingOption>,
    val current: String,
)

// Typed view of getSettingsSchema(). The core flattens the Lua schema exactly
// like the filter list (LuaPluginSource.flattenInto): scalars stay bare keys
// and the option list becomes "options.count" + "options.<i>.value|label".
//
// Follows NoveLA's parseLuaSettingsSchema (LuaSettingsSupport.kt):
// - only `select` exists in the plugin guide, and a missing type means select;
// - an entry without a key, or without any usable option, is dropped;
// - an option without a value is dropped (unlike a filter, a setting has no
//   way to store "empty" as a choice).
// On top of that, an unknown type is logged and skipped so a newer plugin
// never crashes the sheet or renders as a different widget, and duplicate keys
// / option values keep the first so a buggy plugin cannot hand the sheet two
// rows with the same identity.
fun parsePluginSettings(raw: List<Map<String, String>>): List<PluginSettingSpec> =
    raw.mapIndexedNotNull { index, map -> parsePluginSetting(index, map) }
        .distinctBy { it.key }

private const val SELECT_TYPE = "select"

private fun parsePluginSetting(index: Int, map: Map<String, String>): PluginSettingSpec? {
    // The key is the preference name the plugin passes to get_preference, so
    // it is used verbatim, never trimmed.
    val key = map["key"].orEmpty()
    if (key.isBlank()) return null
    val type = map["type"]?.trim().orEmpty()
    if (type.isNotEmpty() && type != SELECT_TYPE) {
        // Widget id only (a short token like "text"); the key and the label are
        // plugin text and stay out of logs.
        MiruLog.event("finder", "settings-schema-unknown-type", "index" to index, "type" to type)
        return null
    }
    val options = parsePluginSettingOptions(map, index)
    if (options.isEmpty()) return null
    return PluginSettingSpec(
        key = key,
        label = map["label"]?.takeIf { it.isNotBlank() } ?: key,
        options = options,
        current = map["current"].orEmpty(),
    )
}

private fun parsePluginSettingOptions(
    map: Map<String, String>,
    index: Int,
): List<PluginSettingOption> {
    // `options` as a keyed Lua table flattens to options.<key> with no count:
    // the plugin guide requires a list, so the setting is dropped, but a bug
    // report should be able to see why it vanished.
    val count = map["options.count"]?.toIntOrNull()?.coerceAtLeast(0)
        ?: run {
            MiruLog.event("finder", "settings-schema-missing-options", "index" to index)
            return emptyList()
        }
    return (0 until count)
        .mapNotNull { index ->
            val value = map["options.$index.value"].orEmpty()
            if (value.isBlank()) return@mapNotNull null
            val label = map["options.$index.label"]?.takeIf { it.isNotBlank() } ?: value
            PluginSettingOption(key = value, label = label)
        }
        .distinctBy { it.key }
}
