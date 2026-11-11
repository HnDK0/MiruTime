package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryBackupPathsTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `already packed body path is kept`() {
        assertEquals(
            "downloads/bodies/abc.html",
            stableBackupBodyPath("downloads/bodies/abc.html", { error("hash must not run") }),
        )
    }

    @Test
    fun `plugin body paths become content addressed and the rewrite is idempotent`() {
        val db = File(tempDir(), "snapshot.db")
        val root = File(tempDir(), "files").apply { mkdirs() }.absolutePath
        val pluginBody = "$root/downloads/text/1/11.html"
        val stableBody = "$root/downloads/bodies/already.html"
        val localBody = "$root/local_books/local-books/novel/chapters/1.html"
        val mangaBody = "$root/downloads/text/3/31.html"
        val outsideBody = "${tempDir()}/downloads/text/5/51.html"

        BundledSQLiteDriver().open(db.absolutePath).use { connection ->
            connection.execute("CREATE TABLE books (bookId INTEGER PRIMARY KEY, installId TEXT)")
            connection.execute("CREATE TABLE chapters (chapterId INTEGER PRIMARY KEY, bookId INTEGER)")
            connection.execute("CREATE TABLE chapter_bodies (chapterId INTEGER PRIMARY KEY, path TEXT)")
            connection.insertBook(1, "chikari")
            connection.insertBook(2, "local-books")
            connection.insertBook(3, "local-manga")
            connection.insertBook(4, "chikari")
            connection.insertBook(5, "chikari")
            connection.insertChapter(11, 1)
            connection.insertChapter(21, 2)
            connection.insertChapter(31, 3)
            connection.insertChapter(41, 4)
            connection.insertChapter(51, 5)
            connection.insertBody(11, pluginBody)
            connection.insertBody(21, localBody)
            connection.insertBody(31, mangaBody)
            connection.insertBody(41, stableBody)
            connection.insertBody(51, outsideBody)
        }

        val rewrites = rewriteCachedBodyPaths(db.absolutePath, root, ::sha256Hex)

        assertEquals(1, rewrites)
        assertEquals(
            root + "/downloads/bodies/" + sha256Hex("downloads/text/1/11.html") + ".html",
            bodyPath(db, 11),
        )
        assertEquals(localBody, bodyPath(db, 21))
        assertEquals(mangaBody, bodyPath(db, 31))
        assertEquals(stableBody, bodyPath(db, 41))
        assertEquals(outsideBody, bodyPath(db, 51))
        // A second pass over the same snapshot must not move anything again.
        assertEquals(0, rewriteCachedBodyPaths(db.absolutePath, root, ::sha256Hex))
        assertTrue(bodyPath(db, 11).startsWith("$root/downloads/bodies/"))
    }

    private fun SQLiteConnection.insertBook(bookId: Long, installId: String) {
        prepare("INSERT INTO books (bookId, installId) VALUES (?, ?)").use { statement ->
            statement.bindLong(1, bookId)
            statement.bindText(2, installId)
            statement.step()
        }
    }

    private fun SQLiteConnection.insertChapter(chapterId: Long, bookId: Long) {
        prepare("INSERT INTO chapters (chapterId, bookId) VALUES (?, ?)").use { statement ->
            statement.bindLong(1, chapterId)
            statement.bindLong(2, bookId)
            statement.step()
        }
    }

    private fun SQLiteConnection.insertBody(chapterId: Long, path: String) {
        prepare("INSERT INTO chapter_bodies (chapterId, path) VALUES (?, ?)").use { statement ->
            statement.bindLong(1, chapterId)
            statement.bindText(2, path)
            statement.step()
        }
    }

    private fun bodyPath(db: File, chapterId: Long): String =
        BundledSQLiteDriver().open(db.absolutePath).use { connection ->
            connection.prepare("SELECT path FROM chapter_bodies WHERE chapterId = ?").use { statement ->
                statement.bindLong(1, chapterId)
                check(statement.step()) { "no body row for $chapterId" }
                statement.getText(0)
            }
        }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-backup-paths").toFile().also { tempDirs += it }
}

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}

private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
    val chars = "0123456789abcdef"
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(chars[value ushr 4]).append(chars[value and 0x0F])
    }
    return out.toString()
}
