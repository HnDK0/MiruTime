package dev.mirutime.app.features.local.data

// Page-name and natural-order helpers shared by the local scanner (catalogue
// and reader) and the local importer. They live in a neutral package so the
// finder and library features do not depend on each other for them.

internal val PageImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

internal fun isPageImageName(name: String): Boolean {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.startsWith(".") || base.startsWith("._")) return false
    val lower = base.lowercase()
    // Thumbnail caches are not pages.
    if (lower.contains("thumb")) return false
    return lower.substringAfterLast('.', "") in PageImageExtensions
}

private val DigitRuns = Regex("\\d+")

internal fun naturalSortKey(name: String): String =
    name.replace(DigitRuns) { it.value.padStart(12, '0') }

internal fun naturalCompare(a: String, b: String): Int =
    naturalSortKey(a).compareTo(naturalSortKey(b))
