package dev.mirutime.core.host

// Namespace of the plugin preferences: the Lua get_preference / set_preference
// globals (NoveLA's "lua_preferences" file) and any host UI that edits a
// setting a plugin declared in getSettingsSchema() both use it, so the two
// sides read and write the same entry.
const val PLUGIN_PREFERENCES_NAMESPACE = "lua_preferences"

interface KeyValueStore {
    fun get(namespace: String, key: String): String?

    fun set(namespace: String, key: String, value: String)

    fun remove(namespace: String, key: String)
}
