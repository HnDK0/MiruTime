package dev.mirutime.data.library.migration

import dev.mirutime.core.reader.migration.ChapterMatchCandidate
import dev.mirutime.core.reader.migration.ChaptersMatcher

private val matcher = ChaptersMatcher()

internal actual fun matchMigrationChapters(
    old: List<MigrationMatchCandidate>,
    new: List<MigrationMatchCandidate>,
): MigrationMatchResult {
    val result = matcher.match(
        old = old.map { it.toCore() },
        new = new.map { it.toCore() },
    )
    return MigrationMatchResult(
        matched = result.matched.map { pair ->
            MigrationMatchPair(old = pair.old.toMigration(), new = pair.new.toMigration())
        },
        unmatchedOld = result.unmatchedOld.map { it.toMigration() },
        unmatchedNew = result.unmatchedNew.map { it.toMigration() },
        score = result.score,
    )
}

private fun MigrationMatchCandidate.toCore() =
    ChapterMatchCandidate(key = key, title = title, volume = volume)

private fun ChapterMatchCandidate.toMigration() =
    MigrationMatchCandidate(key = key, title = title, volume = volume)
