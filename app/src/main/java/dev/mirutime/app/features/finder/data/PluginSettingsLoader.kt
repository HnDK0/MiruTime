package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.app.features.finder.domain.PluginSettingSpec
import dev.mirutime.app.features.finder.domain.parsePluginSettings
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.host.PLUGIN_PREFERENCES_NAMESPACE
import dev.mirutime.core.log.MiruLog

// Loads one plugin's own settings (`getSettingsSchema`) for the settings sheet
// and binds every `select` to the preference entry the plugin itself reads with
// `get_preference`. Kept top-level and source-typed (like loadFilterSchema) so
// it is testable without a live PluginGraph.
//
// Err is a plugin that failed to answer; Ok(empty) is a plugin (or a local
// source) that declares no settings. The sheet keeps the base URL editable
// either way, and shows the failure with Retry.
internal suspend fun loadPluginSettings(
    sourceId: String,
    source: PluginSource?,
    store: KeyValueStore,
    // Called after a value is written, so the caller can drop what depended on
    // it (webnovel's getFilterList reads the same mode preference).
    onChanged: (sourceId: String) -> Unit = {},
): Outcome<List<PluginSetting>> {
    if (source == null) return Outcome.Err(PluginError.ScriptError("plugin not loaded"))
    val raw = when (val result = source.settingsSchema()) {
        is Outcome.Err -> {
            // Class only: UserMessage/ScriptError text may carry a title.
            MiruLog.event(
                "finder",
                "settings-schema-failed",
                "id" to sourceId,
                "error" to result.error::class.simpleName,
            )
            return result
        }
        is Outcome.Ok -> result.value
    }
    return Outcome.Ok(parsePluginSettings(raw).map { spec -> spec.bind(sourceId, store, onChanged) })
}

private fun PluginSettingSpec.bind(
    sourceId: String,
    store: KeyValueStore,
    onChanged: (String) -> Unit,
): PluginSetting.Select {
    // Read on every current() call. The store is not observable, and Compose
    // can recreate this Select while the sheet is open; a value captured once
    // would paint the old choice after that even though set() already ran.
    //
    // The stored value wins, then the plugin's own `current`, and both are
    // checked against the options: a plugin update can retire a value, and
    // most plugins echo get_preference back as `current`, so an invalid entry
    // comes back twice. When neither is an option the first one is shown --
    // display only, nothing is written until the user picks, and one tap is
    // enough to replace the stale entry.
    fun selected(): String {
        val stored = store.get(PLUGIN_PREFERENCES_NAMESPACE, key)
        return listOfNotNull(stored, current)
            .firstOrNull { value -> options.any { it.key == value } }
            ?: options.first().key
    }
    return PluginSetting.Select(
        key = key,
        label = label,
        options = options,
        current = { selected() },
        onChange = { value ->
            // Re-picking the stored value is a no-op: writing it again would
            // invalidate the plugin's filter schema and cost a Lua
            // getFilterList on the next catalogue open for nothing.
            if (value != store.get(PLUGIN_PREFERENCES_NAMESPACE, key)) {
                store.set(PLUGIN_PREFERENCES_NAMESPACE, key, value)
                onChanged(sourceId)
            }
        },
    )
}
