package dev.mirutime.app.core

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.log.MiruLog
import java.net.URI

// The copyable/reportable shape of a plugin failure. One builder serves the
// catalogue, book, updates, reader and download surfaces, so a support report
// always names the source + operation + HTTP status/class + scheme/host/path
// and never a query, a full URL, a cookie, an Authorization value or a UA
// value.
data class PluginErrorReport(
    val detailText: String,
    val reportText: String,
    val code: String,
    val text: PluginErrorText = PluginErrorText(),
) {
    // Verbatim plugin show_error text (sanitized); null for transport failures.
    val title: String? get() = text.title
    val message: String? get() = text.message
    // Kept raw for the Open-login action only; never rendered or copied.
    val authUrl: String? get() = text.authUrl
    val cfHost: String? get() = text.cfHost

    // Copy and Share both use reportText verbatim: the preview, the clipboard
    // and the chooser payload can never disagree.
    fun copyPayload(): String = reportText

    fun sharePayload(): String = reportText
}

const val MAX_PLUGIN_ERROR_DETAIL = 2048

fun buildPluginErrorReport(
    error: PluginError?,
    sourceId: String? = null,
    sourceName: String? = null,
    operation: String? = null,
    fallbackMessage: String? = null,
    identity: String? = null,
): PluginErrorReport {
    val id = sourceId?.takeIf { it.isNotBlank() }
    val name = sourceName?.takeIf { it.isNotBlank() }
    val source = when {
        name == null -> id
        id == null || id == name -> name
        else -> "$name ($id)"
    }
    val lines = mutableListOf<String>()
    if (source != null || operation != null) {
        lines += "source=${source ?: "unknown"} op=${operation ?: "unknown"}"
    }
    var title: String? = null
    var message: String? = null
    var authUrl: String? = null
    var cfHost: String? = null
    var code = "PLG-PLUGIN"

    when (error) {
        null -> fallbackMessage?.takeIf { it.isNotBlank() }?.let { lines += redact(it) }
        is PluginError.UserMessage -> {
            title = redact(error.title).take(MAX_PLUGIN_ERROR_DETAIL)
            message = redact(error.message).take(MAX_PLUGIN_ERROR_DETAIL)
            authUrl = error.authUrl
            if (title.isNotBlank()) lines += title
            if (message.isNotBlank()) lines += message
        }
        is PluginError.Network -> {
            val errorClass = networkClass(error)
            code = if (error.status != null) "PLG-${error.status}" else networkCode(errorClass)
            if (error.status != null) {
                val reason = error.reason?.takeIf { it.isNotBlank() }
                lines += listOfNotNull("HTTP ${error.status}", reason).joinToString(" ")
            } else {
                lines += "class=$errorClass"
            }
            hostPath(error.host, error.path, error.scheme)?.let { lines += "request=$it" }
        }
        is PluginError.CfChallengeRequired -> {
            cfHost = error.host
            code = "PLG-CF"
            lines += "Cloudflare challenge on ${hostPath(error.host, null) ?: error.host}"
        }
        is PluginError.ResponseTooLarge -> {
            code = "PLG-BIG"
            lines += "class=ResponseTooLarge"
        }
        is PluginError.ScriptError -> {
            code = "PLG-SCRIPT"
            // The class is always named; the detail is excerpted only when the
            // exceptionLine guard accepts it.
            lines += "class=ScriptError"
        }
        is PluginError.DnsFailure -> {
            code = "PLG-NET"
            lines += "class=DnsFailure"
            lines += "request=${hostPath(error.host, null) ?: error.host}"
        }
        is PluginError.UnsupportedApi -> {
            lines += "class=UnsupportedApi"
        }
    }

    error?.let { lines += exceptionLine(it) }

    val detail = lines.joinToString("\n").take(MAX_PLUGIN_ERROR_DETAIL)
    val hash = identity?.takeIf { it.isNotBlank() }?.let(MiruLog::idHash) ?: "-"
    return PluginErrorReport(
        detailText = detail,
        reportText = "$detail\ncode=$code idHash=$hash",
        code = code,
        text = PluginErrorText(
            title = title,
            message = message,
            authUrl = authUrl,
            cfHost = cfHost,
        ),
    )
}

// Scheme+host+path only: query, fragment and userinfo never reach the report.
// A scheme is kept when the caller has one, so the request reads as an origin;
// it is validated so no other URL part can ride in on it.
internal fun hostPath(host: String?, path: String?, scheme: String? = null): String? {
    val cleanHost = host?.trim()?.substringAfter("@")?.takeIf { it.isNotBlank() }
    val cleanPath = path?.trim()
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.let { if (it.startsWith("/")) it else if (it.isEmpty()) it else "/$it" }
        .orEmpty()
    val prefix = scheme?.trim()?.lowercase()
        ?.takeIf { SCHEME_TEXT.matches(it) }
        ?.let { "$it://" }
        .orEmpty()
    return when {
        cleanHost == null -> null
        cleanPath.isEmpty() -> "$prefix$cleanHost"
        else -> "$prefix$cleanHost$cleanPath"
    }
}

// Type name only (no stack trace); a ScriptError also carries its detail when
// that detail is a short single line that cannot be a URL, a query, markup or
// a header value. Everything else prints an explicit redaction marker.
private fun exceptionLine(error: PluginError): String {
    val name = error::class.simpleName ?: "PluginError"
    return if (error is PluginError.ScriptError) {
        "exception=$name detail=${scriptDetail(error.detail)}"
    } else {
        "exception=$name"
    }
}

private fun scriptDetail(detail: String): String {
    val text = detail.trim()
    val safe = text.isNotEmpty() &&
        text.length <= 120 &&
        '\n' !in text && '\r' !in text &&
        text.none { it in "<>{}" } &&
        "://" !in text &&
        '?' !in text &&
        !QUERY_LIKE.containsMatchIn(text) &&
        !HEADER_NAME.containsMatchIn(text)
    return if (safe) text else "redacted"
}

// A URL reaching the report as text (a plugin message, a Ktor detail) is
// reduced to host+path; a bare query is dropped.
internal fun redact(text: String): String {
    if (text.isEmpty()) return text
    val withoutHeaders = HEADER_VALUE.replace(text) { match ->
        val name = match.groupValues[1].lowercase()
        "$name=redacted"
    }
    val withoutUrls = URL_TEXT.replace(withoutHeaders) { match ->
        hostPathOnly(match.value)
    }
    return QUERY_TEXT.replace(withoutUrls, "")
        .lineSequence()
        .joinToString("\n")
        .trim()
}

internal fun hostPathOnly(raw: String): String {
    val cleaned = raw.trim()
    if (cleaned.isEmpty()) return cleaned
    val withoutScheme = if ("://" in cleaned) cleaned.substringAfter("://") else cleaned
    val authority = withoutScheme.substringBefore('/').substringAfter('@')
    val path = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        .substringBefore('?')
        .substringBefore('#')
    return if (path.isEmpty()) authority else "$authority/$path"
}

private fun networkClass(error: PluginError.Network): String {
    val cause = error.cause.lowercase()
    return when {
        "timeout" in cause -> "Timeout"
        "ssrf" in cause -> "SsrfBlocked"
        "toolarge" in cause || "too large" in cause -> "ResponseTooLarge"
        "large" in cause -> "ResponseTooLarge"
        "dns" in cause -> "DnsFailure"
        "script" in cause -> "ScriptError"
        "cfchallenge" in cause || "cloudflare" in cause -> "Network"
        else -> "Network"
    }
}

private fun networkCode(errorClass: String): String = when (errorClass) {
    "Timeout" -> "PLG-TIMEOUT"
    "SsrfBlocked" -> "PLG-SSRF"
    "ResponseTooLarge" -> "PLG-BIG"
    "ScriptError" -> "PLG-SCRIPT"
    else -> "PLG-NET"
}

private val HEADER_VALUE = Regex(
    "(?i)\\b(cookie|set-cookie|authorization|proxy-authorization|user-agent)\\s*[:=]\\s*[^\\n]+",
)
private val URL_TEXT = Regex("(?i)\\bhttps?://[^\\s]+")
// A bare query marker only when it carries a key=value pair; a plugin message
// may legitimately contain "?" in prose, but never a query value.
private val QUERY_TEXT = Regex("\\?[^\\s]*=")
private val QUERY_LIKE = Regex("=\\s*\\S")
private val HEADER_NAME = Regex(
    "(?i)\\b(cookie|set-cookie|authorization|proxy-authorization|user-agent)\\b",
)
private val SCHEME_TEXT = Regex("[a-z][a-z0-9+.-]*")

// Diagnostic helper used by the reader repository: the scheme/host/path of a
// URL we are allowed to name in the report.
internal fun uriHostPath(raw: String?): Triple<String?, String?, String?> {
    if (raw.isNullOrBlank()) return Triple(null, null, null)
    return try {
        val uri = URI(raw)
        Triple(uri.scheme, uri.host, uri.path)
    } catch (_: Exception) {
        val scheme = raw.substringBefore("://", missingDelimiterValue = "")
            .takeIf { "://" in raw }
        Triple(scheme, hostPathOnly(raw).substringBefore('/'), null)
    }
}
