package dev.mirutime.app.core

// Human-facing part of a plugin failure. The plugin's own show_error text is
// kept verbatim (after redaction); transport failures have no title/message and
// the UI supplies its localized fallback. authUrl stays raw for the Open-login
// action and is only ever DISPLAYED as host+path.
data class PluginErrorText(
    val title: String? = null,
    val message: String? = null,
    val authUrl: String? = null,
    val cfHost: String? = null,
) {
    val authHostPath: String? get() = authUrl?.let(::hostPathOnly)?.takeIf { it.isNotBlank() }
}
