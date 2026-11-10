package dev.mirutime.app.features.settings.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LocalBookFileEntity
import dev.mirutime.data.library.ProgressEntity
import dev.mirutime.data.library.RestoreJournalStatus
import dev.mirutime.data.library.ReadingSessionEntity
import dev.mirutime.data.library.createDatabaseSnapshot
import dev.mirutime.data.library.importLibraryBackup
import dev.mirutime.data.library.libraryDatabaseAt
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Real Room databases: one backup source, one live target. The source is
// snapshotted with the production helper, imported live with id remapping, and
// checked for both restored child rows and repeat-import idempotency.
@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "restore-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun liveImportRemapsIdsAndIsIdempotent() = runBlocking {
        val sourceDbFile = File(root, "source.db")
        val source = openRoom(sourceDbFile)
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBookId = sourceRepo.saveBook(book("local://shared", title = "Shared"))
        val sourceChapter = sourceRepo.replaceChapters(
            sourceBookId,
            listOf(dev.mirutime.data.library.ChapterSeed(1, "local://shared#chapter=1", "One")),
            at = 10L,
        ).single()
        source.libraryDao().upsertChapter(sourceChapter.copy(read = true, readAt = 11L))
        sourceRepo.saveProgress(
            ProgressEntity(
                bookId = sourceBookId,
                chapterId = sourceChapter.chapterId,
                blockIndex = 1,
                charOffset = 2,
                sentenceIndex = null,
                offsetPx = 0,
                split = false,
                seq = 5L,
                updatedAt = 12L,
            ),
        )
        sourceRepo.saveSession(
            ReadingSessionEntity(
                bookId = sourceBookId,
                chapterId = sourceChapter.chapterId,
                startedAt = 20L,
                endedAt = 30L,
                durationMs = 10L,
                kind = "reading",
                sessionKey = "session-1",
            ),
        )
        sourceRepo.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = sourceBookId,
                uri = "content://local/book.epub",
                displayName = "book.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 8L,
                sha256 = "a".repeat(64),
                copyPath = "local_books/book.epub",
                importedAt = 13L,
            ),
        )
        sourceRepo.saveBookCover(
            BookCoverEntity(
                bookId = sourceBookId,
                remoteUrl = null,
                localPath = File(root, "files/covers/$sourceBookId/cover").absolutePath,
                isUserOverride = false,
            ),
        )
        source.close()

        val snapshot = File(root, "snapshot.db")
        createDatabaseSnapshot(sourceDbFile.absolutePath, snapshot.absolutePath)

        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        val liveRepo = LibraryRepository(live.libraryDao())
        val liveOnlyId = liveRepo.saveBook(book("local://live-only"))
        // Bumps the next generated book/chapter ids so the import must remap.
        liveRepo.replaceChapters(
            liveOnlyId,
            listOf(dev.mirutime.data.library.ChapterSeed(1, "local://live-only#chapter=1", "Live")),
            at = 1L,
        )

        val settingsStore = SettingsStore(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences("restore_instrumented", Context.MODE_PRIVATE)
                    .also { it.edit().clear().commit() },
            ),
        )
        val filesDir = File(root, "files").apply { mkdirs() }
        val service = service(liveDbFile, settingsStore, live, filesDir)
        val settings = PersistedSettings(openAiKeys = "restored-key", provider = "OPENAI")
        val files = listOf(
            "files/local_books/book.epub" to ("book".encodeToByteArray() to "local-copy"),
            "files/covers/$sourceBookId/cover" to (byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to "cover"),
        )
        val archive = archive(snapshot.readBytes(), settings, files)

        val result = service.restore(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(0, live.libraryDao().restoreJournalStatusCount(RestoreJournalStatus))
        assertEquals("restored-key", settingsStore.load().openAiKeys)
        assertEquals("restored-key", AppSettings.openAiKeys)
        assertTrue(File(filesDir, "local_books/book.epub").isFile)
        val restoredCover = File(filesDir, "covers/2/cover")
        assertTrue("cover file should be remapped to the new book id", restoredCover.isFile)

        try {
            val restoredBook = liveRepo.book("local://shared")
            assertNotNull(restoredBook)
            val newBookId = restoredBook!!.bookId
            assertTrue("book id should be remapped", newBookId != sourceBookId)
            val chapters = liveRepo.chapters(newBookId)
            assertEquals(1, chapters.size)
            val newChapterId = chapters.single().chapterId
            assertTrue("chapter id should be remapped", newChapterId != sourceChapter.chapterId)
            assertTrue(chapters.single().read)

            assertEquals(newChapterId, liveRepo.progress(newBookId)?.chapterId)
            val sessions = liveRepo.sessions(newBookId, 10)
            assertEquals(1, sessions.size)
            assertEquals(newChapterId, sessions.single().chapterId)
            assertEquals(newBookId, liveRepo.localBookFile(newBookId)?.bookId)
            val cover = liveRepo.bookCover(newBookId)
            assertNotNull(cover)
            assertEquals(restoredCover.absolutePath, cover!!.localPath)
            assertEquals(
                listOf("local://shared"),
                liveRepo.searchBooks("shared").map { it.url },
            )

            val before = live.libraryDao().storageTotals()

            // Repeat restore: every merge key must hold, so counts stay put.
            val again = service.restore(archive.inputStream())
            assertTrue(again.error, again.applied)
            val after = live.libraryDao().storageTotals()
            assertEquals(before.bookCount, after.bookCount)
            assertEquals(before.chapterCount, after.chapterCount)
            assertEquals(before.localFileCount, after.localFileCount)
            assertEquals(before.coverCount, after.coverCount)
            assertEquals(1, liveRepo.sessions(newBookId, 10).size)
            assertEquals(
                listOf("local://shared"),
                liveRepo.searchBooks("shared").map { it.url },
            )
        } finally {
            live.close()
        }
    }

    @Test
    fun invalidOrTamperedBackupLeavesLiveDatabaseUnchanged() = runBlocking {
        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        val liveRepo = LibraryRepository(live.libraryDao())
        liveRepo.saveBook(book("local://keep"))
        val baseline = live.libraryDao().storageTotals()

        val settingsStore = SettingsStore(
            SharedPreferencesKeyValueStore(
                context.getSharedPreferences("restore_invalid", Context.MODE_PRIVATE)
                    .also { it.edit().clear().commit() },
            ),
        )
        val filesDir = File(root, "files").apply { mkdirs() }
        val service = service(liveDbFile, settingsStore, live, filesDir)

        // Correct hash, but the database bytes are not SQLite.
        val invalidDb = "not-a-database".encodeToByteArray()
        val invalid = service.restore(archive(invalidDb, PersistedSettings(), emptyList()).inputStream())
        assertFalse(invalid.applied)

        // Tampered bytes no longer match the manifest hash.
        val tampered = service.restore(
            archive(validSnapshot(), PersistedSettings(), emptyList(), tamperDatabase = true)
                .inputStream(),
        )
        assertFalse(tampered.applied)

        val after = live.libraryDao().storageTotals()
        assertEquals(baseline.bookCount, after.bookCount)
        assertEquals("local://keep", liveRepo.libraryBooks().single().url)
        assertFalse(File(liveDbFile.parentFile, BackupRestoreService.STAGING_DIR).exists())
        live.close()
    }

    @Test
    fun liveImportPrunesMangaContentWhoseFilesAreMissing() = runBlocking {
        val sourceDbFile = File(root, "manga-source.db")
        val source = openRoom(sourceDbFile)
        val repo = LibraryRepository(source.libraryDao())
        val bookId = repo.saveBook(
            book("local://manga").copy(installId = "local-manga", contentType = "Manga"),
        )
        val chapter = repo.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "local://manga#chapter=1", "One")),
            at = 1L,
        ).single()
        repo.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = bookId,
                uri = "content://local/manga",
                displayName = "manga",
                format = "CBZ",
                mime = null,
                sizeBytes = 10L,
                sha256 = "m".repeat(64),
                copyPath = "local_books/local-manga/missing-folder",
                importedAt = 1L,
            ),
        )
        repo.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapter.chapterId,
                format = "images",
                pagePathsJson = """["local_books/local-manga/missing.jpg"]""",
                totalBytes = 10,
            ),
        )
        repo.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapter.chapterId,
                format = "html",
                path = "local_books/local-manga/missing.html",
                bytes = 5,
                sha256 = "b".repeat(64),
                fetchedAt = 1L,
            ),
        )
        repo.saveBookCover(
            BookCoverEntity(
                bookId = bookId,
                remoteUrl = "https://example.test/cover.jpg",
                localPath = "covers/$bookId/cover",
                sha256 = "c".repeat(64),
                bytes = 10L,
                fetchedAt = 1L,
            ),
        )
        source.close()

        val snapshot = File(root, "manga-snapshot.db")
        createDatabaseSnapshot(sourceDbFile.absolutePath, snapshot.absolutePath)
        val liveDbFile = File(root, "databases/mirutime.db")
        liveDbFile.parentFile?.mkdirs()
        val live = openRoom(liveDbFile)
        val sourceDb = libraryDatabaseAt(context, snapshot)
        try {
            live.importLibraryBackup(source = sourceDb.libraryDao())
            live.libraryDao().pruneMissingRestoredContent { false }
        } finally {
            sourceDb.close()
        }

        val restored = LibraryRepository(live.libraryDao()).libraryBooks().single()
        val restoredChapter = live.libraryDao().chapters(restored.bookId).single()
        // Metadata survives; downloaded-content rows do not.
        assertEquals("local://manga", restored.url)
        assertEquals(restoredChapter.chapterId, chapter.chapterId)
        assertEquals(null, live.libraryDao().chapterPage(restoredChapter.chapterId))
        assertEquals(null, live.libraryDao().chapterBody(restoredChapter.chapterId))
        assertEquals(null, live.libraryDao().localBookFile(restored.bookId))
        // A DB-only backup must not leave a dead cover path: clearing it lets
        // the library use remoteUrl and backfill the file.
        assertEquals(null, live.libraryDao().bookCover(restored.bookId)?.localPath)
        assertEquals(
            "https://example.test/cover.jpg",
            live.libraryDao().bookCover(restored.bookId)?.remoteUrl,
        )
        live.close()
    }

    private suspend fun validSnapshot(): ByteArray {
        val sourceFile = File(root, "invalid-source.db")
        val source = openRoom(sourceFile)
        LibraryRepository(source.libraryDao()).saveBook(book("local://source"))
        source.close()
        val snapshot = File(root, "invalid-snapshot.db")
        createDatabaseSnapshot(sourceFile.absolutePath, snapshot.absolutePath)
        return snapshot.readBytes()
    }

    private fun service(
        liveDbFile: File,
        settingsStore: SettingsStore,
        live: LibraryDatabase,
        filesDir: File,
    ) = BackupRestoreService(
        filesDir = filesDir,
        databasePath = liveDbFile,
        settingsStore = settingsStore,
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
                )
            } finally {
                source.close()
            }
        },
        journalExists = { id ->
            live.libraryDao().restoreJournalCount(
                id,
                id.toString(),
                RestoreJournalStatus,
            ) > 0
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

    private fun archive(
        database: ByteArray,
        settings: PersistedSettings,
        files: List<Pair<String, Pair<ByteArray, String>>>,
        tamperDatabase: Boolean = false,
    ): ByteArray {
        val settingsBytes = BackupJson
            .encodeToString(PersistedSettings.serializer(), settings)
            .encodeToByteArray()
        val storedDatabase = if (tamperDatabase) {
            database.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
        } else {
            database
        }
        val manifest = BackupManifest(
            appVersion = "test",
            createdAt = 0,
            settingsIncluded = true,
            database = BackupEntry(
                DATABASE_ENTRY,
                database.size.toLong(),
                sha256Hex(database),
            ),
            settings = BackupEntry(
                SETTINGS_ENTRY,
                settingsBytes.size.toLong(),
                sha256Hex(settingsBytes),
            ),
            files = files.map { (path, value) ->
                BackupFileEntry(
                    path = path,
                    bookId = 1,
                    kind = value.second,
                    size = value.first.size.toLong(),
                    sha256 = sha256Hex(value.first),
                )
            },
            counts = BackupCounts(localBooks = 1, localFiles = files.size),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.write(
                MANIFEST_ENTRY,
                BackupJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray(),
            )
            zip.write(SETTINGS_ENTRY, settingsBytes)
            zip.write(DATABASE_ENTRY, storedDatabase)
            files.forEach { (path, value) -> zip.write(path, value.first) }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.write(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun book(url: String, title: String = "Book") = BookEntity(
        installId = "local-books",
        url = url,
        title = title,
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
}
