package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryDatabaseSnapshotTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `snapshot includes wal rows and passes integrity check`() {
        val dir = tempDir()
        val source = File(dir, "source.db")
        val target = File(dir, "snapshot.db")

        BundledSQLiteDriver().open(source.absolutePath).use { connection ->
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA user_version = $LibrarySchemaVersion")
            connection.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, value TEXT)")
            connection.execute("INSERT INTO items (value) VALUES ('wal-not-checkpointed')")

            // Room keeps its connection open in production; VACUUM INTO must
            // still see the latest committed WAL row through its own snapshot.
            createDatabaseSnapshot(source.absolutePath, target.absolutePath)
        }

        assertTrue(target.isFile)
        assertTrue(target.length() > 0)
        BundledSQLiteDriver().open(target.absolutePath).use { connection ->
            connection.prepare("PRAGMA integrity_check").use { statement ->
                assertTrue(statement.step())
                assertEquals("ok", statement.getText(0))
            }
            connection.prepare("SELECT value FROM items").use { statement ->
                assertTrue(statement.step())
                assertEquals("wal-not-checkpointed", statement.getText(0))
            }
        }
    }

    @Test
    fun `snapshot rejects a database with another schema version`() {
        val dir = tempDir()
        val source = File(dir, "source-version.db")
        val target = File(dir, "snapshot-version.db")

        BundledSQLiteDriver().open(source.absolutePath).use { connection ->
            connection.execute("PRAGMA user_version = ${LibrarySchemaVersion + 1}")
            connection.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        }

        assertFailsWith<IllegalStateException> {
            createDatabaseSnapshot(source.absolutePath, target.absolutePath)
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-db-snapshot").toFile().also { tempDirs += it }
}

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}
