package dev.mirutime.data.library

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Schema v7: translation identity became language-pair aware, and book
 * translations can persist a chapter-title map. Existing rows are preserved;
 * only exact-key duplicates (same chapter/book + source/target/provider/
 * prompt/pipeline) are collapsed, preferring a COMPLETE row and then the
 * newest one.
 *
 * No destructive fallback runs before this migration: v6 -> v7 is always
 * applied in place.
 */
object LibraryMigrations {
    val MIGRATION_6_7: Migration = Migration(6, 7) { connection ->
        migrateV6ToV7(connection)
    }

    /**
     * Schema v8: additive covering indexes only. Read stats stop scanning the
     * chapters table, and local SAF books stop scanning on URI/sha lookups.
     */
    val MIGRATION_7_8: Migration = Migration(7, 8) { connection ->
        migrateV7ToV8(connection)
    }

    /**
     * Schema v9: additive video progress table. Text [ProgressEntity] stays
     * untouched; a video book's position is milliseconds, not block offsets.
     */
    val MIGRATION_8_9: Migration = Migration(8, 9) { connection ->
        migrateV8ToV9(connection)
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}

/**
 * Migrates a staged backup database (never the live one) to [toVersion].
 *
 * Applies exactly the same in-place steps Room runs for the live migrations,
 * then updates `PRAGMA user_version` and Room's master identity hash so the
 * live import can open the staged file as an ordinary current-version
 * database. The whole step is one transaction: a corrupt or unsupported
 * backup leaves the staged file unchanged and is never published over the
 * live database.
 */
fun migrateBackupDatabase(
    databasePath: String,
    fromVersion: Int,
    toVersion: Int = LibrarySchemaVersion,
) {
    require(toVersion == LibrarySchemaVersion) {
        "Backup migration target $toVersion is not supported (expected $LibrarySchemaVersion)"
    }
    require(fromVersion in MinimumRestorableBackupSchemaVersion until toVersion) {
        "Backup schema $fromVersion cannot be migrated to $toVersion " +
            "(supported: v$MinimumRestorableBackupSchemaVersion..v${toVersion - 1})"
    }

    try {
        BundledSQLiteDriver().open(databasePath).use { connection ->
            connection.exec("PRAGMA busy_timeout = 5000")
            val actual = connection.userVersion()
            if (actual != fromVersion) {
                error(
                    "Backup database schema version $actual does not match the manifest " +
                        "version $fromVersion",
                )
            }
            connection.exec("BEGIN IMMEDIATE")
            try {
                migrateBackupSteps(connection, fromVersion)
                connection.exec("PRAGMA user_version = $toVersion")
                updateRoomIdentity(connection)
                connection.exec("COMMIT")
            } catch (e: Throwable) {
                runCatching { connection.exec("ROLLBACK") }
                throw e
            }
            verifyRestoredDatabase(connection, toVersion)
        }
    } catch (e: Exception) {
        throw IllegalStateException(
            "Backup database migration v$fromVersion -> v$toVersion failed: " +
                (e.message ?: e::class.simpleName),
            e,
        )
    }
}

/**
 * Integrity + version check used while staging. Unlike
 * [verifyDatabaseSnapshot], it accepts a v6 database because the restore path
 * migrates it before verification/import.
 */
fun inspectBackupDatabase(databasePath: String): Int {
    BundledSQLiteDriver().open(databasePath).use { connection ->
        connection.requireIntegrity()
        val version = connection.userVersion()
        if (version !in MinimumRestorableBackupSchemaVersion..LibrarySchemaVersion) {
            error(
                "Backup database schema version $version is not supported " +
                    "(v$MinimumRestorableBackupSchemaVersion is migrated to " +
                    "v$LibrarySchemaVersion; v$LibrarySchemaVersion is current)",
            )
        }
        return version
    }
}

private fun migrateBackupSteps(connection: SQLiteConnection, fromVersion: Int) {
    if (fromVersion < 7) migrateV6ToV7(connection)
    if (fromVersion < 8) migrateV7ToV8(connection)
    if (fromVersion < 9) migrateV8ToV9(connection)
}

private fun migrateV6ToV7(connection: SQLiteConnection) {
    connection.exec("ALTER TABLE chapter_translations ADD COLUMN source TEXT NOT NULL DEFAULT ''")
    connection.exec("ALTER TABLE book_translations ADD COLUMN source TEXT NOT NULL DEFAULT ''")
    connection.exec(
        "ALTER TABLE book_translations ADD COLUMN chapterTitlesJson TEXT NOT NULL DEFAULT '{}'",
    )

    // Legacy rows never had a source, so several sourceHash revisions can
    // now share one identity. Keep the best row per identity.
    connection.exec(
        """
        DELETE FROM chapter_translations
        WHERE EXISTS (
            SELECT 1 FROM chapter_translations AS newer
            WHERE newer.chapterId = chapter_translations.chapterId
                AND newer.source = chapter_translations.source
                AND newer.target = chapter_translations.target
                AND newer.provider = chapter_translations.provider
                AND newer.promptHash = chapter_translations.promptHash
                AND newer.pipelineVersion = chapter_translations.pipelineVersion
                AND (
                    (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        < (CASE WHEN UPPER(chapter_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                    OR (
                        (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                            = (CASE WHEN UPPER(chapter_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        AND newer.updatedAt > chapter_translations.updatedAt
                    )
                    OR (
                        (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                            = (CASE WHEN UPPER(chapter_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        AND newer.updatedAt = chapter_translations.updatedAt
                        AND newer.translationId > chapter_translations.translationId
                    )
                )
        )
        """.trimIndent(),
    )
    // Defensive even though the foreign key cascade should have removed
    // them: a hand-edited/legacy database may have foreign keys off.
    connection.exec(
        "DELETE FROM translation_blocks WHERE translationId NOT IN " +
            "(SELECT translationId FROM chapter_translations)",
    )

    connection.exec(
        """
        DELETE FROM book_translations
        WHERE EXISTS (
            SELECT 1 FROM book_translations AS newer
            WHERE newer.bookId = book_translations.bookId
                AND newer.source = book_translations.source
                AND newer.target = book_translations.target
                AND newer.provider = book_translations.provider
                AND newer.promptHash = book_translations.promptHash
                AND newer.pipelineVersion = book_translations.pipelineVersion
                AND (
                    (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        < (CASE WHEN UPPER(book_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                    OR (
                        (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                            = (CASE WHEN UPPER(book_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        AND newer.updatedAt > book_translations.updatedAt
                    )
                    OR (
                        (CASE WHEN UPPER(newer.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                            = (CASE WHEN UPPER(book_translations.status) = 'COMPLETE' THEN 0 ELSE 1 END)
                        AND newer.updatedAt = book_translations.updatedAt
                        AND newer.translationId > book_translations.translationId
                    )
                )
        )
        """.trimIndent(),
    )

    connection.exec("DROP INDEX IF EXISTS index_chapter_translations_chapterId")
    connection.exec("DROP INDEX IF EXISTS index_book_translations_bookId")
    connection.exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_chapter_translations_chapterId_source_target_provider_promptHash_pipelineVersion " +
            "ON chapter_translations (chapterId, source, target, provider, promptHash, pipelineVersion)",
    )
    connection.exec(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_book_translations_bookId_source_target_provider_promptHash_pipelineVersion " +
            "ON book_translations (bookId, source, target, provider, promptHash, pipelineVersion)",
    )
}

/**
 * v8 byte-for-byte matches [LibraryMigrations.MIGRATION_7_8]: additive indexes
 * only, no data rewrite.
 */
private fun migrateV7ToV8(connection: SQLiteConnection) {
    connection.exec(
        "CREATE INDEX IF NOT EXISTS index_chapters_read_removedAt_bookId_idx " +
            "ON chapters (read, removedAt, bookId, idx)",
    )
    connection.exec(
        "CREATE INDEX IF NOT EXISTS index_local_book_files_uri " +
            "ON local_book_files (uri)",
    )
    connection.exec(
        "CREATE INDEX IF NOT EXISTS index_local_book_files_sha256 " +
            "ON local_book_files (sha256)",
    )
}

/**
 * v9 byte-for-byte matches [LibraryMigrations.MIGRATION_8_9]: additive table
 * and index only, no data rewrite.
 */
private fun migrateV8ToV9(connection: SQLiteConnection) {
    connection.exec(
        "CREATE TABLE IF NOT EXISTS video_progress (" +
            "bookId INTEGER NOT NULL, " +
            "chapterId INTEGER NOT NULL, " +
            "positionMs INTEGER NOT NULL, " +
            "durationMs INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(bookId, chapterId), " +
            "FOREIGN KEY(bookId) REFERENCES books(bookId) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(chapterId) REFERENCES chapters(chapterId) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
    connection.exec(
        "CREATE INDEX IF NOT EXISTS index_video_progress_bookId " +
            "ON video_progress (bookId)",
    )
}

/**
 * Room refuses to open a migrated file whose `room_master_table` still carries
 * an older hash. The live import opens the staged database through Room, so the
 * migrator publishes the current identity itself. `9.json` is the source of
 * truth.
 */
private fun updateRoomIdentity(connection: SQLiteConnection) {
    if (!connection.tableExists("room_master_table")) return
    connection.exec(
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
            "VALUES (42, '$V9_IDENTITY_HASH')",
    )
}

private fun verifyRestoredDatabase(connection: SQLiteConnection, expectedVersion: Int) {
    connection.requireIntegrity()
    val version = connection.userVersion()
    if (version != expectedVersion) {
        error("Migrated database reports schema version $version instead of $expectedVersion")
    }
    REQUIRED_V7_TABLES.forEach { table ->
        if (!connection.tableExists(table)) {
            error("Migrated database is missing table $table")
        }
    }
    REQUIRED_V7_COLUMNS.forEach { (table, columns) ->
        val actual = connection.columns(table)
        columns.forEach { column ->
            if (column !in actual) {
                error("Migrated database is missing column $table.$column")
            }
        }
    }
    REQUIRED_V7_INDEXES.forEach { (index, table) ->
        if (!connection.uniqueIndexExists(index, table)) {
            error("Migrated database is missing unique index $index on $table")
        }
    }
    REQUIRED_V8_INDEXES.forEach { (index, table) ->
        if (!connection.indexExists(index, table)) {
            error("Migrated database is missing index $index on $table")
        }
    }
    REQUIRED_V9_TABLES.forEach { table ->
        if (!connection.tableExists(table)) {
            error("Migrated database is missing table $table")
        }
    }
    REQUIRED_V9_INDEXES.forEach { (index, table) ->
        if (!connection.indexExists(index, table)) {
            error("Migrated database is missing index $index on $table")
        }
    }
}

private const val V9_IDENTITY_HASH = "117dd4519b1602322a7e187aa9956185"

private val REQUIRED_V7_TABLES = listOf(
    "books",
    "chapters",
    "chapter_translations",
    "translation_blocks",
    "book_translations",
)

private val REQUIRED_V7_COLUMNS = mapOf(
    "chapter_translations" to listOf("source"),
    "book_translations" to listOf("source", "chapterTitlesJson"),
)

private val REQUIRED_V7_INDEXES = mapOf(
    "index_chapter_translations_chapterId_source_target_provider_promptHash_pipelineVersion" to
        "chapter_translations",
    "index_book_translations_bookId_source_target_provider_promptHash_pipelineVersion" to
        "book_translations",
)

private val REQUIRED_V8_INDEXES = mapOf(
    "index_chapters_read_removedAt_bookId_idx" to "chapters",
    "index_local_book_files_uri" to "local_book_files",
    "index_local_book_files_sha256" to "local_book_files",
)

private val REQUIRED_V9_TABLES = listOf("video_progress")

private val REQUIRED_V9_INDEXES = mapOf(
    "index_video_progress_bookId" to "video_progress",
)

private fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

private fun SQLiteConnection.userVersion(): Int =
    prepare("PRAGMA user_version").use { statement ->
        if (statement.step()) statement.getLong(0).toInt() else -1
    }

private fun SQLiteConnection.requireIntegrity() {
    prepare("PRAGMA integrity_check").use { statement ->
        val ok = statement.step() && statement.getText(0).equals("ok", ignoreCase = true)
        if (!ok) error("Database integrity check failed")
    }
}

private fun SQLiteConnection.tableExists(table: String): Boolean =
    prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'")
        .use { it.step() }

private fun SQLiteConnection.columns(table: String): Set<String> =
    prepare("PRAGMA table_info('$table')").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.uniqueIndexExists(index: String, table: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_master " +
            "WHERE type = 'index' AND name = '$index' AND tbl_name = '$table' " +
            "AND (sql LIKE '%UNIQUE%' OR sql LIKE '%unique%')",
    ).use { it.step() }

private fun SQLiteConnection.indexExists(index: String, table: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_master " +
            "WHERE type = 'index' AND name = '$index' AND tbl_name = '$table'",
    ).use { it.step() }
