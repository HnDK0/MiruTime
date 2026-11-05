package dev.mirutime.app.features.book.domain

// The header shows the book's other name under the display title. A plugin may
// send an explicit subtitle or only alt titles; both are trimmed, the first
// non-blank wins, and a value equal to the display title is dropped so the
// header never repeats itself.
fun displaySubtitle(
    subtitle: String?,
    altTitles: List<String>,
    title: String? = null,
): String? {
    val direct = subtitle?.trim().orEmpty()
    val alt = altTitles.asSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val value = direct.ifBlank { alt }
    if (value.isBlank()) return null
    if (title != null && value.equals(title.trim(), ignoreCase = true)) return null
    return value
}
