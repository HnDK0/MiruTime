package dev.mirutime.app.features.book.data

/**
 * Merge rules for a manual metadata refresh (FR-012): a field the source now
 * returns wins, but a blank/missing field keeps its stored value instead of
 * wiping it. Pure so the "refresh restores, never erases" rule is testable
 * without a plugin/DB harness.
 */
internal fun mergedRating(fresh: Float, stored: Float): Float =
    if (fresh > 0f) fresh else stored

internal fun mergedText(fresh: String?, stored: String): String =
    fresh?.takeIf { it.isNotBlank() } ?: stored
