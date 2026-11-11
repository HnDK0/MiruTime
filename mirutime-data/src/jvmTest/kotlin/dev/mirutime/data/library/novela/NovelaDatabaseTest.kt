package dev.mirutime.data.library.novela

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NovelaDatabaseTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `reads books, chapters and bodies from a current NoveLA schema`() {
        val path = tempFile("novela-current.db")
        createCurrentSchema(path)

        NovelaDatabase.open(path).use { database ->
            val books = database.books()
            assertEquals(listOf("https://n/a", "https://n/b"), books.map { it.url })

            val book = books.first()
            assertEquals("Alpha", book.title)
            assertEquals("https://n/a.jpg", book.coverUrl)
            assertEquals("Desc", book.description)
            assertEquals("Fantasy,Action", book.genres)
            assertEquals("4.5", book.rating)
            assertEquals("Ongoing", book.status)
            assertEquals("1 Jan", book.lastUpdateDate)
            assertEquals("Cat", book.category)
            assertTrue(book.inLibrary)
            assertEquals("MANGA", book.contentType)
            assertTrue(book.completed)
            assertEquals(10L, book.addedAt)
            assertEquals(20L, book.updatedAt)
            assertEquals(40L, book.lastReadAt)
            assertEquals("hash", book.chaptersListHash)
            assertEquals("https://n/a#2", book.lastReadChapter)

            val minimal = books.last()
            assertEquals("", minimal.coverUrl)
            assertEquals("", minimal.genres)
            assertEquals("", minimal.rating)
            assertEquals("", minimal.status)
            assertEquals("", minimal.lastUpdateDate)
            assertEquals("", minimal.contentType)
            assertEquals(false, minimal.completed)
            assertEquals(false, minimal.inLibrary)
            assertNull(minimal.chaptersListHash)
            assertNull(minimal.lastReadChapter)

            val chapters = database.chapters("https://n/a")
            assertEquals(
                listOf("https://n/a#1", "https://n/a#2", "https://n/a#3"),
                chapters.map { it.url },
            )
            val first = chapters.first()
            assertEquals("https://n/a", first.bookUrl)
            assertEquals("One", first.title)
            assertEquals(1, first.position)
            assertTrue(first.read)
            assertEquals(-3, first.lastReadPosition)
            assertEquals(7, first.lastReadOffset)
            assertEquals(1_700_000_000L, first.uploadedAt)
            assertEquals("Volume 1", first.volume)
            assertNull(chapters.last().uploadedAt)
            assertNull(chapters.last().volume)
            assertTrue(database.chapters("https://n/b").isEmpty())

            val bodies = database.bodies(
                listOf("https://n/a#1", "https://n/unknown", "", "   "),
            )
            assertEquals(listOf("https://n/a#1"), bodies.map { it.chapterUrl })
            assertEquals("body-one", bodies.single().body)
            assertTrue(database.bodies(emptyList()).isEmpty())
        }
    }

    @Test
    fun `bodies accepts more than one statement chunk of urls`() {
        val path = tempFile("novela-chunked.db")
        createCurrentSchema(path)
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare("INSERT INTO ChapterBody (url, body) VALUES (?, ?)").use { statement ->
                repeat(950) { index ->
                    statement.bindText(1, "https://n/chunk/$index")
                    statement.bindText(2, "body-$index")
                    statement.step()
                    statement.reset()
                }
            }
        }

        NovelaDatabase.open(path).use { database ->
            val requested = (0 until 950).map { "https://n/chunk/$it" }
            val bodies = database.bodies(requested)
            assertEquals(950, bodies.size)
            assertEquals(requested.toSet(), bodies.map { it.chapterUrl }.toSet())
            assertEquals("body-949", bodies.single { it.chapterUrl == "https://n/chunk/949" }.body)
        }
    }

    @Test
    fun `legacy schema without optional columns returns defaults`() {
        val path = tempFile("novela-legacy.db")
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execute(
                """
                CREATE TABLE Book (
                    title TEXT NOT NULL,
                    url TEXT NOT NULL PRIMARY KEY,
                    lastReadChapter TEXT,
                    inLibrary INTEGER NOT NULL,
                    coverImageUrl TEXT NOT NULL,
                    description TEXT NOT NULL,
                    lastReadEpochTimeMilli INTEGER NOT NULL,
                    addedToLibraryEpochTimeMilli INTEGER NOT NULL,
                    lastUpdateEpochTimeMilli INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    chaptersListHash TEXT,
                    chaptersLastPage INTEGER
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Book (
                    title, url, lastReadChapter, inLibrary, coverImageUrl, description,
                    lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli,
                    lastUpdateEpochTimeMilli, category, chaptersListHash, chaptersLastPage
                ) VALUES ('Old', 'https://old/b', NULL, 1, '', '', 5, 6, 7, '', NULL, NULL)
                """.trimIndent(),
            )
            connection.execute(
                """
                CREATE TABLE Chapter (
                    title TEXT NOT NULL,
                    url TEXT NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    read INTEGER NOT NULL,
                    lastReadPosition INTEGER NOT NULL,
                    lastReadOffset INTEGER NOT NULL,
                    videoPositionMs INTEGER NOT NULL,
                    videoDurationMs INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Chapter (
                    title, url, bookUrl, position, read, lastReadPosition,
                    lastReadOffset, videoPositionMs, videoDurationMs
                ) VALUES ('One', 'https://old/b#1', 'https://old/b', 1, 1, 2, 3, 0, 0)
                """.trimIndent(),
            )
            connection.execute("CREATE TABLE ChapterBody (url TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)")
            connection.execute("INSERT INTO ChapterBody (url, body) VALUES ('https://old/b#1', 'old-body')")
        }

        NovelaDatabase.open(path).use { database ->
            val book = database.books().single()
            assertEquals("", book.genres)
            assertEquals("", book.rating)
            assertEquals("", book.status)
            assertEquals("", book.lastUpdateDate)
            assertEquals("", book.contentType)
            assertEquals(false, book.completed)
            assertNull(book.lastReadChapter)

            val chapter = database.chapters("https://old/b").single()
            assertNull(chapter.uploadedAt)
            assertNull(chapter.volume)
            assertEquals(2, chapter.lastReadPosition)

            assertEquals("old-body", database.bodies(listOf("https://old/b#1")).single().body)
        }
    }

    @Test
    fun `open rejects a database without the NoveLA tables`() {
        val path = tempFile("not-novela.db")
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execute("CREATE TABLE Chapter (url TEXT NOT NULL PRIMARY KEY)")
        }

        assertFailsWith<NotANovelaDatabaseException> { NovelaDatabase.open(path) }
    }

    private fun tempFile(name: String): String {
        val dir = Files.createTempDirectory("mirutime-novela").toFile()
        tempDirs += dir
        return File(dir, name).absolutePath
    }

    private fun createCurrentSchema(path: String) {
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execute(
                """
                CREATE TABLE Book (
                    title TEXT NOT NULL,
                    url TEXT NOT NULL PRIMARY KEY,
                    completed INTEGER NOT NULL,
                    lastReadChapter TEXT,
                    inLibrary INTEGER NOT NULL,
                    coverImageUrl TEXT NOT NULL,
                    description TEXT NOT NULL,
                    lastReadEpochTimeMilli INTEGER NOT NULL,
                    addedToLibraryEpochTimeMilli INTEGER NOT NULL,
                    lastUpdateEpochTimeMilli INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    chaptersListHash TEXT,
                    chaptersLastPage INTEGER,
                    genres TEXT NOT NULL,
                    rating TEXT NOT NULL,
                    status TEXT NOT NULL,
                    lastUpdateDate TEXT NOT NULL,
                    contentType TEXT NOT NULL
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Book (
                    title, url, completed, lastReadChapter, inLibrary, coverImageUrl,
                    description, lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli,
                    lastUpdateEpochTimeMilli, category, chaptersListHash, chaptersLastPage,
                    genres, rating, status, lastUpdateDate, contentType
                ) VALUES (
                    'Alpha', 'https://n/a', 1, 'https://n/a#2', 1, 'https://n/a.jpg',
                    'Desc', 40, 10, 20, 'Cat', 'hash', 3,
                    'Fantasy,Action', '4.5', 'Ongoing', '1 Jan', 'MANGA'
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Book (
                    title, url, completed, lastReadChapter, inLibrary, coverImageUrl,
                    description, lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli,
                    lastUpdateEpochTimeMilli, category, chaptersListHash, chaptersLastPage,
                    genres, rating, status, lastUpdateDate, contentType
                ) VALUES ('Beta', 'https://n/b', 0, NULL, 0, '', '', 0, 0, 0, '', NULL, NULL,
                    '', '', '', '', '')
                """.trimIndent(),
            )
            connection.execute(
                """
                CREATE TABLE Chapter (
                    title TEXT NOT NULL,
                    url TEXT NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    read INTEGER NOT NULL,
                    lastReadPosition INTEGER NOT NULL,
                    lastReadOffset INTEGER NOT NULL,
                    uploaded INTEGER,
                    videoPositionMs INTEGER NOT NULL,
                    videoDurationMs INTEGER NOT NULL,
                    volume TEXT
                )
                """.trimIndent(),
            )
            // Inserted out of order: the reader must sort by position then url.
            connection.execute(
                """
                INSERT INTO Chapter VALUES (
                    'Three', 'https://n/a#3', 'https://n/a', 2, 0, 0, 0, NULL, 0, 0, NULL
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Chapter VALUES (
                    'Two', 'https://n/a#2', 'https://n/a', 1, 0, 0, 0, NULL, 0, 0, NULL
                )
                """.trimIndent(),
            )
            connection.execute(
                """
                INSERT INTO Chapter VALUES (
                    'One', 'https://n/a#1', 'https://n/a', 1, 1, -3, 7, 1700000000, 0, 0, 'Volume 1'
                )
                """.trimIndent(),
            )
            connection.execute(
                "CREATE TABLE ChapterBody (url TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)",
            )
            connection.execute(
                "INSERT INTO ChapterBody (url, body) VALUES ('https://n/a#1', 'body-one')",
            )
        }
    }
}

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}
