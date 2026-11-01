package dev.mirutime.app.features.finder.domain

// Repository update rule: only install when the catalog version is strictly
// newer than the installed one. Versions are dot-separated numeric segments,
// optionally prefixed with "v"; pre-release/build suffixes are ignored.
// Anything unparseable (including a missing value) is never an update, so a
// bad catalog line cannot trigger a downgrade.
internal fun isNewerVersion(available: String?, installed: String?): Boolean {
    val candidate = parseVersion(available) ?: return false
    val current = parseVersion(installed) ?: return false
    val size = maxOf(candidate.size, current.size)
    for (index in 0 until size) {
        val next = candidate.getOrElse(index) { 0L }
        val previous = current.getOrElse(index) { 0L }
        if (next != previous) return next > previous
    }
    return false
}

private fun parseVersion(raw: String?): List<Long>? {
    val text = raw
        ?.trim()
        ?.removePrefix("v")
        ?.removePrefix("V")
        ?.substringBefore('-')
        ?.substringBefore('+')
        ?.trim()
    if (text.isNullOrEmpty()) return null
    val parts = text.split('.')
    if (parts.isEmpty()) return null
    // The first segment must be numeric; later non-numeric segments make the
    // whole value unparseable rather than silently truncating it.
    return parts.map { it.toLongOrNull() ?: return null }
}
