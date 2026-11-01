package dev.mirutime.translator

/**
 * Chat-completions endpoint for a user-configured OpenAI-compatible base URL.
 * The base is free-form (any host path; `/v1` is common but not required) and
 * the user's path is never rewritten. Only whitespace and a trailing `/` are
 * trimmed; then a base that already ends with `/chat/completions`
 * (case-insensitive) is treated as the full endpoint and returned as-is,
 * otherwise `/chat/completions` is appended exactly once. No other path
 * segment is stripped and no `/v1` is added: a host-only base stays host-only
 * and shows the server's own 404 instead of a silently rewritten path.
 */
fun openAiCompatibleChatEndpoint(baseUrl: String): String {
    val suffix = "/chat/completions"
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix
}
