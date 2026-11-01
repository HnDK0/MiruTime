package dev.mirutime.core.text

// Most plugins return plain text, but some return chapter HTML with block
// tags and <img>. Preserve that HTML (the reader parses it and strips unsafe
// tags); escape only plain text so a literal "<" cannot become markup.
private val HtmlBlockTag = Regex(
    "(?is)<(p|div|br|img|h[1-6]|li|ul|ol|section|blockquote|figure|figcaption|span|em|strong|i|b)\\b[^>]*>",
)

fun chapterHtml(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return if (HtmlBlockTag.containsMatchIn(trimmed)) trimmed else textToParagraphHtml(text)
}

// Plugin chapter text is usually PLAIN TEXT (no HTML). The reader parses block
// tags, so emit one <p> per paragraph: newlines (single or blank-line runs)
// split, raw text is escaped so a literal "<" or "&" cannot inject markup.
fun textToParagraphHtml(text: String): String {
    if (text.isBlank()) return ""
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split(Regex("\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${escapeHtml(it)}</p>" }
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
