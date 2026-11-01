package dev.mirutime.core.api

/** Base type for typed failures returned through [Outcome.Err]. */
interface MiruError

sealed class PluginError : MiruError {
    data class UserMessage(
        val title: String,
        val message: String,
        val authUrl: String? = null,
    ) : PluginError()

    data class Network(
        val cause: String,
        // Optional transport detail filled from the host's NetworkFailureFlag:
        // status/reason for an HTTP failure, scheme+host+path for
        // the request. They are in-memory diagnostics only — never queried
        // from a plugin and never logged with a query string.
        val status: Int? = null,
        val reason: String? = null,
        val host: String? = null,
        val path: String? = null,
        // Kept so the report can say https vs http; host+path alone cannot.
        val scheme: String? = null,
    ) : PluginError()
    data class DnsFailure(val host: String) : PluginError()

    // url/referer/status/isXhr describe the challenged request so the UI can
    // offer Solve for the same operation. They are in-memory only — never logged.
    data class CfChallengeRequired(
        val host: String,
        val url: String? = null,
        val referer: String? = null,
        val status: Int? = null,
        val isXhr: Boolean = false,
    ) : PluginError()
    data class ResponseTooLarge(val limit: Long) : PluginError()
    data class UnsupportedApi(val required: Int, val actual: Int) : PluginError()
    data class ScriptError(val detail: String) : PluginError()
}
