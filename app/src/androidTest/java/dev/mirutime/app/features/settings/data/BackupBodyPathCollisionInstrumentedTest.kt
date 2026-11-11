package dev.mirutime.app.features.settings.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LocalBackupBook
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// A plugin book's cached body is packed under an id-free path. Restoring that
// archive into a library whose ids are already taken by a different book must
// not overwrite the other book's cache file or point both rows at one file.
@RunWith(AndroidJUnit4::class)
class BackupBodyPathCollisionInstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "body-collision-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoringIntoAnotherLibraryDoesNotOverwriteItsBody() = runBlocking {
        val filesDir = File(root, "files").apply { mkdirs() }
        val sourceDbFile = File(root, "source.db")
        val archive = File(root, "archive.zip")
        val source = openRoom(sourceDbFile)
        val oldBookId: Long
        val oldChapterId: Long
        val sourceBooks: List<LocalBackupBook>
        try {
            val sourceRepo = LibraryRepository(source.libraryDao())
            oldBookId = sourceRepo.saveBook(pluginBook(SOURCE_URL))
            oldChapterId = sourceRepo.replaceChapters(
                oldBookId,
                listOf(ChapterSeed(1, "$SOURCE_URL#chapter=1", "One")),
                at = 1L,
            ).single().chapterId
            val sourceBody = File(filesDir, "downloads/text/$oldBookId/$oldChapterId.html")
                .apply {
                    parentFile?.mkdirs()
                    writeText(ARCHIVE_BODY)
                }
            sourceRepo.saveChapterBody(
                ChapterBodyEntity(
                    chapterId = oldChapterId,
                    format = "html",
                    path = sourceBody.absolutePath,
                    bytes = ARCHIVE_BODY.length.toLong(),
                    sha256 = "a".repeat(64),
                    fetchedAt = 1L,
                ),
            )
            sourceRepo.setChapterDownloaded(oldChapterId, true)
            sourceBooks = sourceRepo.localBackupBooks()
        } finally {
            source.close()
        }

        val backup = BackupService(
            databasePath = sourceDbFile,
            filesDir = filesDir,
            cacheDir = File(root, "cache").apply { mkdirs() },
            appVersion = "test",
            localBooks = { sourceBooks },
            localCovers = { emptyList() },
            pluginKvSnapshot = { PluginKvBackup() },
            localSourcesSnapshot = { LocalSourcesBackup() },
        ).createBackupTo(archive)
        assertTrue(backup.error, backup.success)
        assertEquals(1, backup.localFileCount)
        val bodyEntries = zipEntries(archive).filter { it.startsWith("files/downloads/bodies/") }
        assertEquals(1, bodyEntries.size)
        assertTrue(zipEntries(archive).none { it.startsWith("files/downloads/text/") })

        // The source library is gone (another device). The target library
        // already uses the same ids for a different book with its own cache.
        filesDir.deleteRecursively()
        filesDir.mkdirs()
        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        try {
            val liveRepo = LibraryRepository(live.libraryDao())
            live.libraryDao().upsertBook(pluginBook(LIVE_URL).copy(bookId = oldBookId))
            live.libraryDao().upsertChapter(
                ChapterEntity(
                    chapterId = oldChapterId,
                    bookId = oldBookId,
                    idx = 1,
                    url = "$LIVE_URL#chapter=1",
                    title = "One",
                    updatedAt = 1L,
                ),
            )
            val liveBody = File(filesDir, "downloads/text/$oldBookId/$oldChapterId.html")
                .apply {
                    parentFile?.mkdirs()
                    writeText(LIVE_BODY)
                }
            live.libraryDao().upsertChapterBody(
                ChapterBodyEntity(
                    chapterId = oldChapterId,
                    format = "html",
                    path = liveBody.absolutePath,
                    bytes = LIVE_BODY.length.toLong(),
                    sha256 = "b".repeat(64),
                    fetchedAt = 2L,
                ),
            )

            val service = service(liveDbFile, live, filesDir)
            val restored = service.restore(archive.inputStream())
            assertTrue(restored.error, restored.applied)

            val restoredBook = liveRepo.libraryBooks().single { it.url == SOURCE_URL }
            val restoredChapter = liveRepo.chapters(restoredBook.bookId).single()
            val restoredBody = live.libraryDao().chapterBody(restoredChapter.chapterId)
            assertNotNull("restored body must be kept", restoredBody)
            assertTrue(restoredBody!!.path.contains("/downloads/bodies/"))
            assertEquals(ARCHIVE_BODY, File(restoredBody.path).readText())

            val liveBookBody = live.libraryDao().chapterBody(oldChapterId)
            assertNotNull("the other book's body must survive", liveBookBody)
            assertEquals(
                File(filesDir, "downloads/text/$oldBookId/$oldChapterId.html").absolutePath,
                liveBookBody!!.path,
            )
            assertEquals(LIVE_BODY, File(liveBookBody.path).readText())
            assertNotEquals(liveBookBody.path, restoredBody.path)
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
                context.getSharedPreferences("body_collision", Context.MODE_PRIVATE)
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

    private fun pluginBook(url: String) = BookEntity(
        installId = "chikari",
        url = url,
        title = "Plugin Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 1,
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
        const val SOURCE_URL = "https://example.test/book/archive"
        const val LIVE_URL = "https://example.test/book/live"
        const val ARCHIVE_BODY = "<p>archive</p>"
        const val LIVE_BODY = "<p>live</p>"
    }
}
