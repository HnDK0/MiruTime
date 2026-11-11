package dev.mirutime.app.features.settings.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.RestoreJournalStatus
import dev.mirutime.data.library.importLibraryBackup
import dev.mirutime.data.library.libraryDatabaseAt
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A NoveLA database + zip is converted by NovelaBackupStager and imported by
// the real BackupRestoreService staging pipeline, so the test covers the whole
// conversion-and-import path, not just the mapper.
@RunWith(AndroidJUnit4::class)
class NovelaBackupRestoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "novela-restore-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoresNovelaArchiveThroughStagingPipeline() = runBlocking {
        val novelaDb = File(root, "database.sqlite3")
        writeNovelaFixture(novelaDb)
        val archive = writeNovelaZip(File(root, "novela.zip"), novelaDb)

        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        val filesDir = File(root, "files").apply { mkdirs() }
        val stager = NovelaBackupStager(
            context = context,
            filesDir = filesDir,
            appVersion = "test",
            installedPlugins = { emptyList() },
            existingInstallIdForUrl = { url -> live.libraryDao().bookByUrl(url)?.installId },
        )
        val service = service(live, liveDbFile, filesDir, stager)
        try {
            val result = service.restore(archive.inputStream())
            assertTrue(result.error, result.applied)

            val repo = LibraryRepository(live.libraryDao())
            val books = repo.libraryBooks()
            assertEquals(2, books.size)
            // A history-only NoveLA row is not imported at all.
            assertNull(live.libraryDao().bookByUrl(HISTORY_BOOK_URL))
            val pluginBook = books.single { it.url == PLUGIN_BOOK_URL }
            val hostBook = books.single { it.url == HOST_BOOK_URL }
            assertEquals("testplugin", pluginBook.installId)
            assertEquals("other.test", hostBook.installId)

            val chapters = repo.chapters(pluginBook.bookId)
            assertEquals(3, chapters.size)
            assertEquals(listOf(1, 2, 3), chapters.map { it.idx })
            assertTrue(chapters[0].downloaded)
            assertEquals(false, chapters[2].downloaded)
            assertTrue(chapters[1].read)

            val progress = repo.progress(pluginBook.bookId)
            assertNotNull(progress)
            assertEquals(chapters[1].chapterId, progress!!.chapterId)
            assertEquals(1, progress.blockIndex)
            assertEquals(42, progress.charOffset)

            val coverPath = live.libraryDao().bookCover(pluginBook.bookId)?.localPath
            assertNotNull(coverPath)
            val coverFile = File(coverPath!!)
            assertTrue(coverFile.isFile)
            assertTrue(coverFile.absolutePath.startsWith(File(filesDir, "covers").absolutePath))
            assertEquals(8, coverFile.readBytes().size)

            val body = live.libraryDao().chapterBody(chapters[0].chapterId)
            assertNotNull(body)
            val bodyText = File(body!!.path).readText()
            assertTrue(bodyText.contains("<p>"))

            val pluginFile = File(filesDir, "mirutime_plugins/lua_plugins/testplugin.lua")
            assertTrue(pluginFile.isFile)
            assertTrue(pluginFile.readText().contains("testplugin"))

            // Identity merge: a second restore must not duplicate rows.
            val before = live.libraryDao().storageTotals()
            val again = service.restore(archive.inputStream())
            assertTrue(again.error, again.applied)
            val after = live.libraryDao().storageTotals()
            assertEquals(before.bookCount, after.bookCount)
            assertEquals(before.chapterCount, after.chapterCount)
            assertEquals(2, repo.libraryBooks().size)
            assertEquals(chapters.size, repo.chapters(pluginBook.bookId).size)
        } finally {
            live.close()
        }
    }

    private fun service(
        live: LibraryDatabase,
        liveDbFile: File,
        filesDir: File,
        stager: NovelaBackupStager,
    ) = BackupRestoreService(
        filesDir = filesDir,
        databasePath = liveDbFile,
        settingsStore = SettingsStore(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences("novela_restore", Context.MODE_PRIVATE)
                    .also { it.edit().clear().commit() },
            ),
        ),
        pluginKvStore = NoopPluginKvBackupStore,
        localSourcesStore = NoopLocalSourcesBackupStore,
        appDataDir = root,
        importDatabase = { sourceFile, coverPathFor, journalId, journalNow, onCommitted ->
            val source = libraryDatabaseAt(context, sourceFile)
            try {
                live.importLibraryBackup(
                    source = source.libraryDao(),
                    coverPathFor = coverPathFor,
                    journalId = journalId,
                    journalNow = journalNow,
                    onCommitted = onCommitted,
                    importCachedBodies = true,
                )
            } finally {
                source.close()
            }
        },
        stageNovelaBackup = { input, staging, progress, stage ->
            stager.stage(input, staging, progress, stage)
        },
        journalExists = { id ->
            live.libraryDao().restoreJournalCount(id, id.toString(), RestoreJournalStatus) > 0
        },
        deleteJournal = { id ->
            live.libraryDao().deleteRestoreJournal(id, RestoreJournalStatus)
        },
        deleteAllJournals = {
            live.libraryDao().deleteRestoreJournals(RestoreJournalStatus)
        },
    )

    private fun openRoom(file: File): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(
            context = context,
            name = file.absolutePath,
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    private fun writeNovelaFixture(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE Book (url TEXT PRIMARY KEY, title TEXT, coverImageUrl TEXT, " +
                    "description TEXT, genres TEXT, rating TEXT, status TEXT, " +
                    "lastUpdateDate TEXT, category TEXT, inLibrary INTEGER, contentType TEXT, " +
                    "completed INTEGER, addedToLibraryEpochTimeMilli INTEGER, " +
                    "lastUpdateEpochTimeMilli INTEGER, lastReadEpochTimeMilli INTEGER, " +
                    "chaptersListHash TEXT, lastReadChapter TEXT)",
            )
            db.execSQL(
                "CREATE TABLE Chapter (url TEXT PRIMARY KEY, bookUrl TEXT, title TEXT, " +
                    "position INTEGER, read INTEGER, lastReadPosition INTEGER, " +
                    "lastReadOffset INTEGER, uploaded INTEGER, volume TEXT)",
            )
            db.execSQL("CREATE TABLE ChapterBody (url TEXT PRIMARY KEY, body TEXT)")
            db.insertOrThrow(
                "Book",
                null,
                ContentValues().apply {
                    put("url", PLUGIN_BOOK_URL)
                    put("title", "Plugin Book")
                    put("coverImageUrl", "https://example.test/cover.png")
                    put("description", "About")
                    put("genres", "romance, romance, drama")
                    put("rating", "4.5")
                    put("status", "Ongoing")
                    put("lastUpdateDate", "Jan 1")
                    put("category", "Reading")
                    put("inLibrary", 1)
                    put("contentType", "novel")
                    put("completed", 0)
                    put("addedToLibraryEpochTimeMilli", 100L)
                    put("lastUpdateEpochTimeMilli", 200L)
                    put("lastReadEpochTimeMilli", 1_700_000_000_000L)
                    put("lastReadChapter", "$PLUGIN_BOOK_URL/chapter/2")
                },
            )
            db.insertOrThrow(
                "Book",
                null,
                ContentValues().apply {
                    put("url", HOST_BOOK_URL)
                    put("title", "Host Book")
                    put("coverImageUrl", "")
                    put("description", "")
                    put("genres", "")
                    put("rating", "")
                    put("status", "")
                    put("lastUpdateDate", "")
                    put("category", "")
                    put("inLibrary", 1)
                    put("contentType", "novel")
                    put("completed", 1)
                },
            )
            db.insertOrThrow(
                "Book",
                null,
                ContentValues().apply {
                    put("url", HISTORY_BOOK_URL)
                    put("title", "History Book")
                    put("coverImageUrl", "")
                    put("description", "")
                    put("genres", "")
                    put("rating", "")
                    put("status", "")
                    put("lastUpdateDate", "")
                    put("category", "Reading")
                    put("inLibrary", 0)
                    put("contentType", "novel")
                    put("completed", 0)
                },
            )
            db.insertOrThrow(
                "Chapter",
                null,
                ContentValues().apply {
                    put("url", "$PLUGIN_BOOK_URL/chapter/1")
                    put("bookUrl", PLUGIN_BOOK_URL)
                    put("title", "One")
                    put("position", 1)
                    put("read", 1)
                    put("lastReadPosition", 0)
                    put("lastReadOffset", 0)
                    put("uploaded", 1_600_000_000L)
                },
            )
            db.insertOrThrow(
                "Chapter",
                null,
                ContentValues().apply {
                    put("url", "$PLUGIN_BOOK_URL/chapter/2")
                    put("bookUrl", PLUGIN_BOOK_URL)
                    put("title", "Two")
                    put("position", 2)
                    put("read", 1)
                    put("lastReadPosition", 2)
                    put("lastReadOffset", 42)
                    put("uploaded", 1_600_000_100L)
                },
            )
            db.insertOrThrow(
                "Chapter",
                null,
                ContentValues().apply {
                    put("url", "$PLUGIN_BOOK_URL/chapter/3")
                    put("bookUrl", PLUGIN_BOOK_URL)
                    put("title", "Three")
                    put("position", 3)
                    put("read", 0)
                },
            )
            db.insertOrThrow(
                "Chapter",
                null,
                ContentValues().apply {
                    put("url", "$HOST_BOOK_URL/chapter/1")
                    put("bookUrl", HOST_BOOK_URL)
                    put("title", "One")
                    put("position", 1)
                },
            )
            db.insertOrThrow(
                "Chapter",
                null,
                ContentValues().apply {
                    put("url", "$HISTORY_BOOK_URL/chapter/1")
                    put("bookUrl", HISTORY_BOOK_URL)
                    put("title", "One")
                    put("position", 1)
                },
            )
            db.insertOrThrow(
                "ChapterBody",
                null,
                ContentValues().apply {
                    put("url", "$PLUGIN_BOOK_URL/chapter/1")
                    put("body", "First paragraph.\n\nSecond <b>paragraph</b>.")
                },
            )
        } finally {
            db.close()
        }
    }

    private fun writeNovelaZip(target: File, database: File): File {
        val coverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val coverDir = Base64.getEncoder().encodeToString(PLUGIN_BOOK_URL.encodeToByteArray())
        val plugin = """
            id = "testplugin"
            name = "Test Plugin"
            version = "9.9.9"
            baseUrl = "https://example.test/"
        """.trimIndent()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.write("database.sqlite3", database.readBytes())
            zip.write("books/$coverDir/__cover_image", coverBytes)
            zip.write("lua_extensions/testplugin.lua", plugin.encodeToByteArray())
            zip.write("settings.json", "{}".encodeToByteArray())
        }
        assertTrue(target.isFile)
        return target
    }

    private fun ZipOutputStream.write(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val PLUGIN_BOOK_URL = "https://example.test/book/one"
        const val HOST_BOOK_URL = "https://other.test/book/two"
        const val HISTORY_BOOK_URL = "https://history.test/book/three"
    }
}
