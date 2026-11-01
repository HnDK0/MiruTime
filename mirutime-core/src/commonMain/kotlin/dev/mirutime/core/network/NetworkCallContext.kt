package dev.mirutime.core.network

// Per-plugin-session diagnostic label for the request trace: which Lua entry
// point triggered the HTTP call and which ARGUMENT NAMES it received (names
// only — values may be a search query and must never be logged). One instance
// per LuaPluginSource; the session mutex serializes calls, so a plain volatile
// field pair is enough.
class NetworkCallContext {
    @Volatile
    private var activeOperation: String? = null

    @Volatile
    private var activeArgs: String? = null

    fun begin(operation: String, argNames: Collection<String> = emptyList()) {
        activeOperation = operation
        activeArgs = argNames.joinToString(",").ifEmpty { "-" }
    }

    fun end() {
        activeOperation = null
        activeArgs = null
    }

    fun operation(): String? = activeOperation

    fun argNames(): String? = activeArgs
}
