package dev.mirutime.core.engine

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.ThreeArgFunction

// The base URL the user sets in Manage extensions lives in the host's plugin
// metadata, but a script builds its requests from its own `baseUrl` global and
// from what it derives from it while it loads (API roots, referers). While the
// chunk runs, the first `baseUrl = "..."` it executes stores the host's value
// instead of the declared one, so both follow the setting.
//
// Without an override the host value is the one the script declares, and
// nothing changes. A value that is not an http(s) URL is ignored, and the
// declared trailing slash is kept: scripts append either "/path" or "path".
// The sandbox globals have no metatable of their own; if one is ever there,
// the load runs untouched rather than losing its __newindex.
internal fun <T> Globals.pinningBaseUrl(hostBaseUrl: String, load: () -> T): T {
    if (!hostBaseUrl.startsWith("http://", ignoreCase = true) &&
        !hostBaseUrl.startsWith("https://", ignoreCase = true)
    ) {
        return load()
    }
    if (getmetatable() != null) return load()
    val hook = LuaTable()
    hook.set(
        LuaValue.NEWINDEX,
        object : ThreeArgFunction() {
            override fun call(table: LuaValue, key: LuaValue, value: LuaValue): LuaValue {
                val declared = if (key.isstring() && value.isstring() && key.tojstring() == "baseUrl") {
                    value.tojstring()
                } else {
                    null
                }
                table.rawset(key, if (declared != null) LuaValue.valueOf(shaped(hostBaseUrl, declared)) else value)
                return LuaValue.NONE
            }
        },
    )
    setmetatable(hook)
    try {
        return load()
    } finally {
        // A script that installed a metatable of its own on _G keeps it.
        if (getmetatable() === hook) setmetatable(null)
    }
}

private fun shaped(hostBaseUrl: String, declared: String): String =
    when {
        hostBaseUrl == declared -> declared
        declared.endsWith("/") -> hostBaseUrl.trimEnd('/') + "/"
        else -> hostBaseUrl.trimEnd('/')
    }
