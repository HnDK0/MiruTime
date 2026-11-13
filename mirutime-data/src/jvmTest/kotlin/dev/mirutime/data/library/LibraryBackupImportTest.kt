package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBackupImportTest {
    private val open = mutableListOf<LibraryDatabase>()

    @AfterTest
    fun tearDown() {
        open.forEach { it.close() }
    }

    @Test
    fun `import remaps every child id and is idempotent`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBook = sourceRepo.saveBook(book("https://s/book"))
        val sourceChapters = sourceRepo.replaceChapters(
            sourceBook,
            listOf(
                ChapterSeed(1, "https://s/book/1", "One"),
                ChapterSeed(2, "https://s/book/2", "Two"),
            ),
            at = 10L,
        )
        val sourceChapter = sourceChapters.first().chapterId
        sourceRepo.saveBook(
            sourceRepo.book("https://s/book")!!.copy(
                lastChapterId = sourceChapters.last().chapterId,
                lastChapterIndex = 2,
            ),
        )

        sourceRepo.saveProgress(
            ProgressEntity(sourceBook, sourceChapter, 1, 2, null, 0, false, 5L, 12L),
        )
        sourceRepo.saveVideoProgress(
            bookId = sourceBook,
            chapterId = sourceChapter,
            positionMs = 45_000,
            durationMs = 100_000,
            at = 13L,
        )
        sourceRepo.saveSession(
            ReadingSessionEntity(
                bookId = sourceBook,
                chapterId = sourceChapter,
                startedAt = 20L,
                endedAt = 30L,
                durationMs = 10L,
                kind = "reading",
                sessionKey = "session-1",
            ),
        )
        source.libraryDao().upsertDownloadTask(
            DownloadTaskEntity(
                bookId = sourceBook,
                chapterId = sourceChapter,
                kind = "chapter",
                state = "DONE",
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
        sourceRepo.saveChapterBody(
            ChapterBodyEntity(
                chapterId = sourceChapter,
                format = "html",
                path = "chapter_bodies/1.html",
                bytes = 3,
                sha256 = "a".repeat(64),
                fetchedAt = 1L,
            ),
        )
        sourceRepo.saveChapterPage(
            ChapterPageEntity(
                chapterId = sourceChapter,
                format = "images",
                pagePathsJson = "[\"pages/1.jpg\"]",
            ),
        )
        sourceRepo.saveBookCover(
            BookCoverEntity(
                bookId = sourceBook,
                remoteUrl = "https://s/cover.jpg",
                localPath = "covers/$sourceBook/cover",
            ),
        )
        val tagId = source.libraryDao().upsertTag(
            TagEntity(name = "Fantasy", normalizedName = "fantasy", kind = "PLUGIN", createdAt = 1L),
        )
        sourceRepo.saveBookTag(BookTagEntity(bookId = sourceBook, tagId = tagId))
        sourceRepo.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = sourceBook,
                uri = "content://local/book.epub",
                displayName = "book.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 3L,
                sha256 = "b".repeat(64),
                copyPath = "local_books/book.epub",
                importedAt = 1L,
            ),
        )
        val highlightId = sourceRepo.saveHighlight(
            HighlightEntity(
                bookId = sourceBook,
                chapterId = sourceChapter,
                blockIndex = 0,
                startOffset = 1,
                endOffset = 2,
                textKind = "original",
                colorArgb = 0xFF00FF00,
                sourceHash = "hash",
                parserVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        assertTrue(highlightId > 0)
        val translationId = sourceRepo.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = sourceChapter,
                source = "en",
                target = "ru",
                provider = "OPENAI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "1",
                status = "READY",
                updatedAt = 5L,
            ),
        )
        sourceRepo.saveTranslationBlock(
            TranslationBlockEntity(
                translationId = translationId,
                blockIndex = 0,
                sourceTextHash = "h",
                translatedText = "перевод",
            ),
        )
        sourceRepo.saveBookTranslation(
            BookTranslationEntity(
                bookId = sourceBook,
                source = "en",
                target = "ru",
                provider = "OPENAI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "1",
                title = "Книга",
                chapterTitlesJson = "{\"1\":\"Первая\"}",
                status = "READY",
                updatedAt = 5L,
            ),
        )
        sourceRepo.saveTtsChunk(
            TtsChunkEntity(
                chapterId = sourceChapter,
                blockIndex = 0,
                sentenceIndex = 0,
                text = "text",
                startOffset = 0,
                endOffset = 4,
            ),
        )

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val liveOnly = targetRepo.saveBook(book("https://t/live"))
        targetRepo.replaceChapters(
            liveOnly,
            listOf(ChapterSeed(1, "https://t/live/1", "Live")),
            at = 1L,
        )
        // Existing book with a user cover override: merge must keep it.
        targetRepo.saveBookCover(
            BookCoverEntity(
                bookId = liveOnly,
                remoteUrl = "https://t/live-cover.jpg",
                localPath = "covers/$liveOnly/user",
                isUserOverride = true,
            ),
        )

        target.importLibraryBackup(
            source.libraryDao(),
            pageSize = 1,
            coverPathFor = { old, new, path ->
                path?.replace("covers/$old/", "covers/$new/")
            },
        )

        val imported = assertNotNull(targetRepo.book("https://s/book"))
        assertNotEquals(sourceBook, imported.bookId)
        val importedChapters = targetRepo.chapters(imported.bookId)
        assertEquals(listOf("One", "Two"), importedChapters.map { it.title })
        val importedChapter = importedChapters.first().chapterId
        assertNotEquals(sourceChapter, importedChapter)
        assertEquals(importedChapters.last().chapterId, imported.lastChapterId)

        assertEquals(importedChapter, targetRepo.progress(imported.bookId)?.chapterId)
        val importedVideo = assertNotNull(
            targetRepo.videoProgress(imported.bookId, importedChapter),
        )
        assertEquals(45_000, importedVideo.positionMs)
        assertEquals(100_000, importedVideo.durationMs)
        assertEquals(13L, importedVideo.updatedAt)
        assertEquals(importedChapter, targetRepo.sessions(imported.bookId, 10).single().chapterId)
        assertEquals(imported.bookId, targetRepo.downloadTasks().single().bookId)
        assertEquals(importedChapter, targetRepo.downloadTasks().single().chapterId)
        assertEquals(importedChapter, targetRepo.chapterBody(importedChapter)?.chapterId)
        assertEquals(importedChapter, targetRepo.chapterPage(importedChapter)?.chapterId)
        assertEquals(imported.bookId, targetRepo.localBookFile(imported.bookId)?.bookId)
        assertEquals("covers/${imported.bookId}/cover", targetRepo.bookCover(imported.bookId)?.localPath)
        assertEquals(listOf("Fantasy"), targetRepo.tagsForBook(imported.bookId).map { it.name })
        assertEquals(importedChapter, targetRepo.highlights(imported.bookId).single().chapterId)

        val importedTranslation = targetRepo.translationBlocks(
            target.libraryDao().chapterTranslation(
                importedChapter, "en", "ru", "OPENAI", "p", "s", "1",
            )!!.translationId,
        )
        assertEquals("перевод", importedTranslation.single().translatedText)
        assertEquals(importedChapter, targetRepo.ttsChunks(importedChapter).single().chapterId)
        val importedBookTranslation = targetRepo.bookTranslation(
            imported.bookId, "en", "ru", "OPENAI", "p", "1",
        )
        assertNotNull(importedBookTranslation)
        assertEquals("{\"1\":\"Первая\"}", importedBookTranslation.chapterTitlesJson)
        // The user override on the unrelated live book is untouched.
        assertEquals("covers/$liveOnly/user", targetRepo.bookCover(liveOnly)?.localPath)

        val before = target.libraryDao().storageTotals()
        target.importLibraryBackup(
            source.libraryDao(),
            pageSize = 1,
            coverPathFor = { old, new, path ->
                path?.replace("covers/$old/", "covers/$new/")
            },
        )
        val after = target.libraryDao().storageTotals()
        assertEquals(before.bookCount, after.bookCount)
        assertEquals(before.chapterCount, after.chapterCount)
        assertEquals(before.localFileCount, after.localFileCount)
        assertEquals(before.coverCount, after.coverCount)
        assertEquals(1, targetRepo.highlights(imported.bookId).size)
        assertEquals(1, targetRepo.sessions(imported.bookId, 10).size)
        assertEquals(1, targetRepo.downloadTasks().size)
        assertEquals(1, targetRepo.translationBlocks(
            target.libraryDao().chapterTranslation(
                importedChapter, "en", "ru", "OPENAI", "p", "s", "1",
            )!!.translationId,
        ).size)
        assertEquals(1, targetRepo.ttsChunks(importedChapter).size)
    }

    @Test
    fun `restore journal is written in the transaction and hidden from migrations`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())
        sourceRepo.saveBook(book("https://s/journal"))
        val target = openRoom()

        target.importLibraryBackup(
            source.libraryDao(),
            journalId = 4242L,
            journalNow = 99L,
        )

        val dao = target.libraryDao()
        assertEquals(1, dao.restoreJournalCount(4242L, "4242", RestoreJournalStatus))
        assertTrue(dao.bookMigrations().isEmpty())

        assertEquals(1, dao.deleteRestoreJournal(4242L, RestoreJournalStatus))
        assertEquals(0, dao.restoreJournalCount(4242L, "4242", RestoreJournalStatus))
    }

    @Test
    fun `import preserves an existing book shelf and user cover`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBook = sourceRepo.saveBook(book("https://s/book").copy(shelfId = "completed"))
        sourceRepo.saveBookCover(
            BookCoverEntity(
                bookId = sourceBook,
                remoteUrl = "https://s/cover.jpg",
                localPath = "covers/$sourceBook/backup",
            ),
        )

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val liveBook = targetRepo.saveBook(book("https://s/book").copy(shelfId = "reading"))
        targetRepo.saveBookCover(
            BookCoverEntity(
                bookId = liveBook,
                remoteUrl = "https://s/override.jpg",
                localPath = "covers/$liveBook/user",
                isUserOverride = true,
            ),
        )

        val report = target.importLibraryBackup(
            source.libraryDao(),
            coverPathFor = { _, _, path -> path },
        )

        assertEquals(liveBook, report.bookIdMap[sourceBook])
        assertEquals("reading", targetRepo.book("https://s/book")?.shelfId)
        assertEquals("covers/$liveBook/user", targetRepo.bookCover(liveBook)?.localPath)
        assertTrue(liveBook in report.preservedCoverBookIds)
    }

    @Test
    fun `import leaves existing cache rows for explicit user cleanup`() = runTest {
        val source = openRoom()
        LibraryRepository(source.libraryDao()).saveBook(book("https://s/source"))

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val cacheBook = targetRepo.saveBook(
            book("https://cache/book").copy(installId = "plugin-one"),
        )
        val cacheChapter = targetRepo.replaceChapters(
            cacheBook,
            listOf(ChapterSeed(1, "https://cache/book/1", "Cached")),
            at = 1L,
        ).single()
        targetRepo.saveChapterBody(
            ChapterBodyEntity(
                chapterId = cacheChapter.chapterId,
                format = "html",
                path = "chapter_bodies/cache.html",
                bytes = 1,
                sha256 = "c".repeat(64),
                fetchedAt = 1L,
            ),
        )
        targetRepo.saveChapterPage(
            ChapterPageEntity(
                chapterId = cacheChapter.chapterId,
                format = "images",
                pagePathsJson = """["pages/cache.jpg"]""",
            ),
        )
        targetRepo.saveBookCover(
            BookCoverEntity(
                bookId = cacheBook,
                remoteUrl = "https://cache/cover.jpg",
                localPath = "covers/$cacheBook/cover",
            ),
        )
        target.libraryDao().upsertSourceIcon(
            SourceIconEntity(
                installId = "plugin-one",
                version = "1",
                urlHash = "icon",
                localPath = "icons/icon.png",
            ),
        )

        target.importLibraryBackup(source.libraryDao())

        assertNotNull(target.libraryDao().chapterBody(cacheChapter.chapterId))
        assertNotNull(target.libraryDao().chapterPage(cacheChapter.chapterId))
        assertNotNull(target.libraryDao().bookCover(cacheBook))
        assertNotNull(target.libraryDao().sourceIcon("plugin-one", "1", "icon"))
    }

    @Test
    fun `restore normalizes key urls and preserves live membership`() = runTest {
        val realUrl = "https://s/keyed"
        val keyUrl = libraryBookKey("local-books", realUrl)

        val source = openRoom()
        LibraryRepository(source.libraryDao()).saveBook(
            book(keyUrl).copy(inLibrary = false, title = ""),
        )

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val live = targetRepo.saveBook(book(realUrl).copy(inLibrary = true, title = "Live"))

        target.importLibraryBackup(source.libraryDao())

        val imported = assertNotNull(target.libraryDao().book(live))
        assertEquals(realUrl, imported.url)
        assertEquals("Live", imported.title)
        assertTrue(imported.inLibrary)
        assertEquals(1, target.libraryDao().bookCount())
    }

    @Test
    fun `restore imports history and backfills only inserted books`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())

        val inserted = sourceRepo.saveBook(book("https://s/inserted").copy(lastReadAt = 10L))
        val matchedExplicit = sourceRepo.saveBook(book("https://s/matched").copy(lastReadAt = 10L))
        source.libraryDao().touchHistoryAndRecord(matchedExplicit, null, 1, 20L)
        sourceRepo.saveBook(book("https://s/quiet").copy(lastReadAt = 10L))

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        targetRepo.saveBook(book("https://s/matched").copy(inLibrary = true))
        targetRepo.saveBook(book("https://s/quiet").copy(inLibrary = true))

        target.importLibraryBackup(source.libraryDao())

        val historyUrls = target.libraryDao().history(10).map { it.url }.toSet()
        // Inserted: backfilled from lastReadAt. Matched with an explicit row:
        // imported. Matched without one: not backfilled.
        assertEquals(setOf("https://s/inserted", "https://s/matched"), historyUrls)
    }

    @Test
    fun `restore keeps a newer live reading position over an older archive`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBook = sourceRepo.saveBook(book("https://s/continue").copy(lastReadAt = 10L))
        val sourceChapter = sourceRepo.replaceChapters(
            sourceBook,
            listOf(ChapterSeed(1, "https://s/continue/1", "One")),
            at = 1L,
        ).single()
        sourceRepo.saveBook(
            book("https://s/continue").copy(
                lastChapterId = sourceChapter.chapterId,
                lastChapterIndex = 1,
                lastReadAt = 10L,
            ),
        )

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val targetBook = targetRepo.saveBook(book("https://s/continue").copy(lastReadAt = 20L))
        val liveChapter = targetRepo.replaceChapters(
            targetBook,
            listOf(ChapterSeed(1, "https://s/continue/1", "One")),
            at = 1L,
        ).single()
        targetRepo.saveBook(
            book("https://s/continue").copy(
                lastChapterId = liveChapter.chapterId,
                lastChapterIndex = 1,
                lastReadAt = 20L,
            ),
        )

        target.importLibraryBackup(source.libraryDao())

        assertEquals(liveChapter.chapterId, target.libraryDao().book(targetBook)?.lastChapterId)
        assertEquals(20L, target.libraryDao().book(targetBook)?.lastReadAt)
    }

    @Test
    fun `restore applies a newer archive reading position over an older live row`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBook = sourceRepo.saveBook(book("https://s/continue").copy(lastReadAt = 30L))
        val sourceChapter = sourceRepo.replaceChapters(
            sourceBook,
            listOf(ChapterSeed(1, "https://s/continue/1", "One")),
            at = 1L,
        ).single()
        sourceRepo.saveBook(
            book("https://s/continue").copy(
                lastChapterId = sourceChapter.chapterId,
                lastChapterIndex = 1,
                lastReadAt = 30L,
            ),
        )

        val target = openRoom()
        val targetRepo = LibraryRepository(target.libraryDao())
        val targetBook = targetRepo.saveBook(book("https://s/continue").copy(lastReadAt = 20L))
        val liveChapter = targetRepo.replaceChapters(
            targetBook,
            listOf(ChapterSeed(1, "https://s/continue/1", "One")),
            at = 1L,
        ).single()
        targetRepo.saveBook(
            book("https://s/continue").copy(
                lastChapterId = liveChapter.chapterId,
                lastChapterIndex = 1,
                lastReadAt = 20L,
            ),
        )

        target.importLibraryBackup(source.libraryDao())

        assertEquals(30L, target.libraryDao().book(targetBook)?.lastReadAt)
        assertEquals(liveChapter.chapterId, target.libraryDao().book(targetBook)?.lastChapterId)
    }

    @Test
    fun `restore resets downloaded flags without a local body`() = runTest {
        val source = openRoom()
        val sourceRepo = LibraryRepository(source.libraryDao())

        val pluginBook = sourceRepo.saveBook(book("https://s/plugin").copy(installId = "plugin-x"))
        val pluginChapter = sourceRepo.replaceChapters(
            pluginBook,
            listOf(ChapterSeed(1, "https://s/plugin/1", "One")),
            at = 1L,
        ).single()
        source.libraryDao().upsertChapter(pluginChapter.copy(downloaded = true))

        val localBook = sourceRepo.saveBook(book("https://s/local"))
        val localChapter = sourceRepo.replaceChapters(
            localBook,
            listOf(ChapterSeed(1, "https://s/local/1", "One")),
            at = 1L,
        ).single()
        source.libraryDao().upsertChapter(localChapter.copy(downloaded = true))
        sourceRepo.saveChapterBody(
            ChapterBodyEntity(
                chapterId = localChapter.chapterId,
                format = "html",
                path = "chapter_bodies/local.html",
                bytes = 3,
                sha256 = "c".repeat(64),
                fetchedAt = 1L,
            ),
        )
        sourceRepo.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = localBook,
                uri = "content://local/book2.epub",
                displayName = "book2.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 3L,
                sha256 = "d".repeat(64),
                copyPath = "local_books/book2.epub",
                importedAt = 1L,
            ),
        )

        val target = openRoom()
        target.importLibraryBackup(source.libraryDao())

        val importedPlugin = assertNotNull(
            target.libraryDao().bookByInstallAndUrl("plugin-x", "https://s/plugin"),
        )
        assertTrue(
            target.libraryDao().chapters(importedPlugin.bookId).none { it.downloaded },
        )

        val importedLocal = assertNotNull(
            target.libraryDao().bookByInstallAndUrl("local-books", "https://s/local"),
        )
        assertTrue(
            target.libraryDao().chapters(importedLocal.bookId).single().downloaded,
        )
    }

    @Test
    fun `restore skips plugin chapter bodies by default`() = runTest {
        val source = openRoom()
        val sourceBook = LibraryRepository(source.libraryDao()).saveBook(
            book("https://s/plugin").copy(installId = "plugin-x"),
        )
        seedPluginCache(source, sourceBook)

        val target = openRoom()
        target.importLibraryBackup(source.libraryDao())

        val imported = assertNotNull(
            target.libraryDao().bookByInstallAndUrl("plugin-x", "https://s/plugin"),
        )
        val chapterId = target.libraryDao().chapters(imported.bookId).single().chapterId
        assertNull(target.libraryDao().chapterBody(chapterId))
    }

    @Test
    fun `restore with cached bodies imports plugin bodies but not pages`() = runTest {
        val source = openRoom()
        val sourceBook = LibraryRepository(source.libraryDao()).saveBook(
            book("https://s/plugin").copy(installId = "plugin-x"),
        )
        seedPluginCache(source, sourceBook)

        val target = openRoom()
        target.importLibraryBackup(source.libraryDao(), importCachedBodies = true)

        val imported = assertNotNull(
            target.libraryDao().bookByInstallAndUrl("plugin-x", "https://s/plugin"),
        )
        val chapterId = target.libraryDao().chapters(imported.bookId).single().chapterId
        assertEquals("chapter_bodies/plugin.html", target.libraryDao().chapterBody(chapterId)?.path)
        assertNull(target.libraryDao().chapterPage(chapterId))
    }

    @Test
    fun `restore carries opds catalogs before the books that reference them`() = runTest {
        val entry = "https://archive.org/details/vie_des_abeilles_2002_librivox"
        val source = openRoom()
        source.libraryDao().upsertOpdsCatalog(
            OpdsCatalogEntity(
                id = "cat-1",
                name = "Archive.org",
                url = "https://archive.org/services/opds",
                addedAt = 5L,
            ),
        )
        val sourceRepo = LibraryRepository(source.libraryDao())
        val sourceBook = sourceRepo.saveBook(
            book(entry).copy(installId = "opds:cat-1", contentType = "Audio", totalChapters = 2),
        )
        sourceRepo.replaceChapters(
            sourceBook,
            listOf(
                ChapterSeed(1, "https://archive.org/download/x/1.mp3", "01"),
                ChapterSeed(2, "https://archive.org/download/x/2.mp3", "02"),
            ),
            at = 6L,
        )

        val target = openRoom()
        target.importLibraryBackup(source.libraryDao())

        val catalog = target.libraryDao().opdsCatalogs().single()
        assertEquals("cat-1", catalog.id)
        assertEquals("Archive.org", catalog.name)
        val imported = assertNotNull(
            target.libraryDao().bookByInstallAndUrl("opds:cat-1", entry),
        )
        assertEquals("Audio", imported.contentType)
        assertEquals(2, target.libraryDao().chapters(imported.bookId).size)
    }

    private suspend fun seedPluginCache(source: LibraryDatabase, bookId: Long) {
        val repository = LibraryRepository(source.libraryDao())
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://s/plugin/1", "One")),
            at = 1L,
        ).single()
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapter.chapterId,
                format = "html",
                path = "chapter_bodies/plugin.html",
                bytes = 3,
                sha256 = "e".repeat(64),
                fetchedAt = 1L,
            ),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapter.chapterId,
                format = "images",
                pagePathsJson = """["pages/plugin.jpg"]""",
            ),
        )
    }

    private fun openRoom(): LibraryDatabase =
        Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { open += it }

    private fun book(url: String) = BookEntity(
        installId = "local-books",
        url = url,
        title = "Book",
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
}
