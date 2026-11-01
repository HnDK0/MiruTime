package dev.mirutime.core.engine.binding

import dev.mirutime.core.api.PluginError

// Transport-failure detail for ONE catalogue call. The Lua contract lets a
// plugin swallow `http_get(...).success == false` and return an empty page; the
// host must not present that as "nothing found", so the network-failure flag
// turns it into a typed error. NetworkBindings marks every completed exchange
// (2xx = success, anything else / exception = failure); LuaPluginSource calls
// begin() before catalog/search/catalogFiltered and consume() after: an empty
// Ok with a pending failure becomes Err(detail), while a genuinely empty 200
// page stays Ok(empty).
//
// The detail is a typed PluginError.Network carrying status/reason/host/path,
// or the CfChallengeRequiredError a failed solve produced. Session calls are
// serialized by LuaPluginSource's mutex, so a plain reference is enough;
// @Volatile keeps the batch bridge (http_get_batch) safe.
class NetworkFailureFlag {
    @Volatile
    private var failure: PluginError? = null

    fun begin() {
        failure = null
    }

    fun markFailure() {
        failure = PluginError.Network("request failed")
    }

    fun markFailure(error: PluginError) {
        failure = error
    }

    fun markFailure(
        status: Int?,
        reason: String? = null,
        host: String? = null,
        path: String? = null,
        scheme: String? = null,
    ) {
        failure = PluginError.Network(
            cause = "network",
            status = status,
            reason = reason,
            host = host,
            path = path,
            scheme = scheme,
        )
    }

    // A later 2xx clears the marker: a successful fallback (FWN search/list)
    // must not inherit the failure of the request before it.
    fun markSuccess() {
        failure = null
    }

    fun consume(): PluginError? {
        val value = failure
        failure = null
        return value
    }
}
