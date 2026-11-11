package dev.mirutime.app.features.settings.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.RestoreJournalStatus
import dev.mirutime.data.library.importLibraryBackup
import dev.mirutime.data.library.libraryDatabaseAt
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Backs up a plugin book's downloaded chapter body with the real BackupService,
// wipes the cached files (a reinstall), then restores the archive into a second
// database with the real BackupRestoreService. The chapter must stay readable
// offline while manga pages stay out of the archive.
@RunWith(AndroidJUnit4::class)
class BackupPluginBodiesRoundTripInstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "plugin-body-backup-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun downloadedPluginBodiesSurviveBackupAndRestore() = runBlocking {
        val filesDir = File(root, "files").apply { mkdirs() }
        val sourceDbFile = File(root, "source.db")
        val source = openRoom(sourceDbFile)
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBookId = sourceRepo.saveBook(pluginBook())
        val chapters = sourceRepo.replaceChapters(
            sourceBookId,
            listOf(
                ChapterSeed(1, "$BOOK_URL#chapter=1", "One"),
                ChapterSeed(2, "$BOOK_URL#chapter=2", "Two"),
            ),
            at = 1L,
        )
        val bodyText = "<p>body</p>"
        val bodyFile = File(filesDir, "downloads/text/$sourceBookId/${chapters[0].chapterId}.html")
            .apply {
                parentFile?.mkdirs()
                writeText(bodyText)
            }
        sourceRepo.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapters[0].chapterId,
                format = "html",
                path = bodyFile.absolutePath,
                bytes = bodyText.length.toLong(),
                sha256 = "b".repeat(64),
                fetchedAt = 1L,
            ),
        )
        sourceRepo.setChapterDownloaded(chapters[0].chapterId, true)
        // Manga pages are never packed; only the DB row exists on the source.
        sourceRepo.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapters[1].chapterId,
                format = "images",
                pagePathsJson = """["${filesDir.absolutePath}/downloads/manga/fake/00001.jpg"]""",
                totalBytes = 10,
            ),
        )

        val archive = File(root, "archive.zip")
        val backupRequest = BackupService(
            databasePath = sourceDbFile,
            filesDir = filesDir,
            cacheDir = File(root, "cache").apply { mkdirs() },
            appVersion = "test",
            localBooks = { LibraryRepository(source.libraryDao()).localBackupBooks() },
            localCovers = { LibraryRepository(source.libraryDao()).localBackupCovers() },
            pluginKvSnapshot = { PluginKvBackup() },
            localSourcesSnapshot = { LocalSourcesBackup() },
        )
        try {
            val backup = backupRequest.createBackupTo(archive)
            assertTrue(backup.error, backup.success)
            assertEquals(1, backup.localFileCount)
        } finally {
            source.close()
        }
        val bodyEntry = "files/downloads/bodies/" +
            sha256Hex("downloads/text/$sourceBookId/${chapters[0].chapterId}.html".encodeToByteArray()) +
            ".html"
        assertTrue(zipEntries(archive).contains(bodyEntry))
        assertTrue(zipEntries(archive).none { it.startsWith("files/downloads/text/") })
        assertTrue(zipEntries(archive).none { it.startsWith("files/downloads/manga") })

        // Simulate a reinstall: the offline files are gone, only the archive is left.
        assertTrue(File(filesDir, "downloads").deleteRecursively())
        assertFalse(bodyFile.exists())

        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        try {
            val service = service(liveDbFile, live, filesDir)
            val restored = service.restore(archive.inputStream())
            assertTrue(restored.error, restored.applied)

            val liveRepo = LibraryRepository(live.libraryDao())
            val book = liveRepo.libraryBooks().single { it.url == BOOK_URL }
            assertEquals("chikari", book.installId)
            val restoredChapters = liveRepo.chapters(book.bookId)
            assertEquals(2, restoredChapters.size)
            val downloaded = restoredChapters.single { it.downloaded }
            assertEquals(1, downloaded.idx)

            val body = live.libraryDao().chapterBody(downloaded.chapterId)
            assertNotNull("downloaded plugin body must be restored", body)
            assertTrue(
                "restored body must use the id-free store",
                body!!.path.contains("/downloads/bodies/"),
            )
            assertTrue(File(body.path).readText().contains(bodyText))

            // Pages were never packed, so no page row may claim a download.
            val pageChapter = restoredChapters.single { it.idx == 2 }
            assertNull(live.libraryDao().chapterPage(pageChapter.chapterId))
            assertFalse(pageChapter.downloaded)
        } finally {
            live.close()
        }
    }

    private fun service(
        liveDbFile: File,
        live: LibraryDatabase,
        filesDir: File,
    ) = BackupRestoreService(
        filesDir = filesDir,
        databasePath = liveDbFile,
        settingsStore = SettingsStore(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences("plugin_body_restore", Context.MODE_PRIVATE)
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
        journalExists = { id ->
            live.libraryDao().restoreJournalCount(id, id.toString(), RestoreJournalStatus) > 0
        },
        deleteJournal = { id ->
            live.libraryDao().deleteRestoreJournal(id, RestoreJournalStatus)
        },
        deleteAllJournals = {
            live.libraryDao().deleteRestoreJournals(RestoreJournalStatus)
        },
        pruneMissingRestoredContent = {
            live.libraryDao().pruneMissingRestoredContent { path ->
                path.isNotBlank() && File(path).exists()
            }
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

    private fun zipEntries(file: File): Set<String> {
        val names = linkedSetOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun pluginBook() = BookEntity(
        installId = "chikari",
        url = BOOK_URL,
        title = "Plugin Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 2,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
        firstSeenAt = 1L,
        addedToLibraryAt = 1L,
    )

    private companion object {
        const val BOOK_URL = "https://example.test/book/plugin"
    }
}
