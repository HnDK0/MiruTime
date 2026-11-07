package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Writes a consistent single-file snapshot of [databasePath] to [targetPath].
 *
 * VACUUM INTO reads one SQLite snapshot (WAL included) and writes a fresh
 * database, so the result never needs the live -wal/-shm files and cannot be
 * half-written. The snapshot is opened again and checked with
 * PRAGMA integrity_check + PRAGMA user_version before the caller may pack it.
 */
fun createDatabaseSnapshot(
    databasePath: String,
    targetPath: String,
    expectedSchemaVersion: Int = LibrarySchemaVersion,
) {
    val driver = BundledSQLiteDriver()
    driver.open(databasePath).use { connection ->
        connection.execute("PRAGMA busy_timeout = 5000")
        val escapedTarget = targetPath.replace("'", "''")
        connection.execute("VACUUM INTO '$escapedTarget'")
    }
    verifyDatabaseSnapshot(targetPath, expectedSchemaVersion)
}

/**
 * Drops history-only books from a snapshot so a "library only" backup does not
 * carry rows the user never added to the library. FK cascades remove chapters,
 * progress, sessions, downloads, covers, translations, TTS and local files;
 * the standalone FTS row is removed explicitly. The result is re-verified.
 */
suspend fun pruneHistoryOnlyBooks(
    targetPath: String,
    expectedSchemaVersion: Int = LibrarySchemaVersion,
) {
    var removed = 0
    BundledSQLiteDriver().open(targetPath).use { connection ->
        connection.execute("PRAGMA foreign_keys = ON")
        while (true) {
            currentCoroutineContext().ensureActive()
            connection.execute(
                "DELETE FROM books WHERE bookId IN (" +
                    "SELECT bookId FROM books WHERE inLibrary = 0 LIMIT $PRUNE_CHUNK)",
            )
            val changes = connection.changes()
            removed += changes
            if (changes == 0) break
        }
        connection.execute(
            "DELETE FROM book_search WHERE rowid NOT IN (SELECT bookId FROM books)",
        )
        if (removed > 0) {
            // VACUUM is an optimization, not part of correctness. On a nearly
            // full device it can fail after the DELETE already committed;
            // verification below still decides whether the snapshot is valid.
            runCatching { connection.execute("VACUUM") }
        }
    }
    verifyDatabaseSnapshot(targetPath, expectedSchemaVersion)
}

private fun SQLiteConnection.changes(): Int =
    prepare("SELECT changes()").use { statement ->
        if (statement.step()) statement.getInt(0) else 0
    }

private const val PRUNE_CHUNK = 500

/**
 * Opens [databasePath] read-only in spirit (the driver needs a read-write
 * handle) and requires a clean integrity check and [expectedSchemaVersion].
 * Used before a staged restore replaces the live database.
 */
fun verifyDatabaseSnapshot(
    databasePath: String,
    expectedSchemaVersion: Int = LibrarySchemaVersion,
) {
    BundledSQLiteDriver().open(databasePath).use { connection ->
        connection.prepare("PRAGMA integrity_check").use { statement ->
            val ok = statement.step() && statement.getText(0).equals("ok", ignoreCase = true)
            if (!ok) error("Database integrity check failed")
        }
        val version = connection.prepare("PRAGMA user_version").use { statement ->
            if (statement.step()) statement.getLong(0).toInt() else -1
        }
        if (version != expectedSchemaVersion) {
            error("Unexpected database schema version: $version")
        }
    }
}

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}
