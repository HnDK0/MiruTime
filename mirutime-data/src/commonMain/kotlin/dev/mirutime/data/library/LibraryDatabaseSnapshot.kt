package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

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
