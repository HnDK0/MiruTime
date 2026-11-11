package dev.mirutime.app.features.settings.data

import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.LocalBackupBook
import dev.mirutime.data.library.LocalBackupCover
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `archive has manifest settings database and only in-library local files`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val copy = write(filesDir, "local_books/local-books/novel.epub", "copy")
        val body = write(filesDir, "local_books/local-books/novel/chapters/1.html", "body")
        val cover = write(filesDir, "covers/7/cover", "cover")
        // Coil's durable image cache must never enter the archive.
        write(filesDir, "image_cache/entry/cover", "cached")
        write(filesDir, "local_books/local-manga/folder/chapters/1/00001.jpg", "page")
        write(filesDir, "local_books/local-books/hidden.epub", "hidden")
        val outside = write(root, "outside/cover.jpg", "outside")

        val settings = PersistedSettings(
            provider = "OPENAI",
            openAiKeys = "sk-secret",
            pinnedSources = listOf("source-a"),
            nextRuleId = 123L,
        )
        val books = listOf(
            LocalBackupBook(
                bookId = 7,
                installId = "local-books",
                title = "Novel",
                inLibrary = true,
                copyPath = "local_books/local-books/novel.epub",
                format = "EPUB",
                coverLocalPath = cover.absolutePath,
                bodyPaths = listOf("local_books/local-books/novel/chapters/1.html"),
            ),
            LocalBackupBook(
                bookId = 8,
                installId = "local-manga",
                title = "Manga",
                inLibrary = true,
                copyPath = "local_books/local-manga/folder",
                format = "CBZ",
                coverLocalPath = null,
                bodyPaths = emptyList(),
            ),
            LocalBackupBook(
                bookId = 9,
                installId = "local-books",
                title = "History only",
                inLibrary = false,
                copyPath = "local_books/local-books/hidden.epub",
                format = "EPUB",
                coverLocalPath = null,
                bodyPaths = emptyList(),
            ),
            LocalBackupBook(
                bookId = 10,
                installId = "plugin-one",
                title = "Outside",
                inLibrary = true,
                copyPath = null,
                format = "EPUB",
                coverLocalPath = outside.absolutePath,
                bodyPaths = emptyList(),
            ),
        )

        var snapshotTarget: File? = null
        val service = BackupService(
            databasePath = File(root, "live.db"),
            filesDir = filesDir,
            cacheDir = cacheDir,
            appVersion = "1.2.3",
            localBooks = { books },
            localCovers = {
                listOf(
                    LocalBackupCover(
                        bookId = 10,
                        installId = "plugin-one",
                        title = "Outside",
                        coverLocalPath = outside.absolutePath,
                        isUserOverride = false,
                    ),
                )
            },
            settingsSnapshot = { settings },
            pluginKvSnapshot = { PluginKvBackup() },
            localSourcesSnapshot = { LocalSourcesBackup() },
            snapshotDatabase = { target, _ ->
                snapshotTarget = target
                target.writeText("db-bytes")
                DatabaseSnapshot(target, target.length(), sha256Hex("db-bytes".encodeToByteArray()))
            },
            clock = { 42L },
        )

        val output = ByteArrayOutputStream()
        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        assertEquals(3, result.localBookCount)
        assertEquals(4, result.localFileCount)
        assertEquals(0, result.readerFileCount)
        assertEquals(10, result.entryCount)
        assertFalse(snapshotTarget!!.exists())

        val entries = readZip(output.toByteArray())
        assertEquals(
            setOf(
                "manifest.json",
                "settings.json",
                "reader.json",
                "settings/plugin-kv.json",
                "settings/local-sources.json",
                "database/mirutime.db",
                "files/local_books/local-books/novel.epub",
                "files/local_books/local-books/novel/chapters/1.html",
                "files/covers/7/cover",
                "files/_external/10/cover/cover.jpg",
            ),
            entries.keys.filterNot { it.endsWith("/") }.toSet(),
        )
        assertTrue(entries.keys.none { it.contains("hidden.epub") })
        assertTrue(entries.keys.none { it.contains("image_cache") })
        assertTrue(entries.keys.none { it.contains("local-manga") })
        assertTrue(entries.keys.any { it == "files/covers/7/cover" })

        val manifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            entries.getValue("manifest.json").decodeToString(),
        )
        assertEquals(BackupFormatVersion, manifest.formatVersion)
        assertEquals("1.2.3", manifest.appVersion)
        assertEquals(LibrarySchemaVersion, manifest.schemaVersion)
        assertEquals(42L, manifest.createdAt)
        assertTrue(manifest.settingsIncluded)
        assertTrue(manifest.readerIncluded)
        assertEquals("reader.json", manifest.reader?.name)
        assertEquals("database/mirutime.db", manifest.database.name)
        assertEquals("settings.json", manifest.settings?.name)
        assertEquals(sha256Hex(entries.getValue("database/mirutime.db")), manifest.database.sha256)
        assertEquals(sha256Hex(entries.getValue("settings.json")), manifest.settings?.sha256)
        assertEquals(3, manifest.counts.localBooks)
        assertEquals(4, manifest.counts.localFiles)
        manifest.files.forEach { file ->
            assertEquals(file.sha256, sha256Hex(entries.getValue(file.path)))
            assertEquals(file.size, entries.getValue(file.path).size.toLong())
        }

        val settingsJson = Json.parseToJsonElement(
            entries.getValue("settings.json").decodeToString(),
        ).jsonObject
        assertEquals("OPENAI", settingsJson.getValue("provider").jsonPrimitive.content)
        assertEquals("sk-secret", settingsJson.getValue("openAiKeys").jsonPrimitive.content)
        assertEquals(123L, settingsJson.getValue("nextRuleId").jsonPrimitive.content.toLong())
        assertEquals(
            copy.readText(),
            entries.getValue("files/local_books/local-books/novel.epub").decodeToString(),
        )
        assertEquals(
            body.readText(),
            entries.getValue("files/local_books/local-books/novel/chapters/1.html").decodeToString(),
        )
    }


    @Test
    fun `archive does not carry device-local backup folders`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val service = BackupService(
            databasePath = File(root, "live.db"),
            filesDir = filesDir,
            cacheDir = cacheDir,
            appVersion = "test",
            localBooks = { emptyList() },
            localCovers = { emptyList() },
            settingsSnapshot = {
                PersistedSettings(
                    openAiKeys = "key",
                    backupDirectory = "content://tree/device-backup",
                    exportDirectory = "content://tree/device-export",
                )
            },
            pluginKvSnapshot = { PluginKvBackup() },
            localSourcesSnapshot = { LocalSourcesBackup() },
            snapshotDatabase = { target, _ ->
                target.writeText("db")
                DatabaseSnapshot(
                    file = target,
                    size = target.length(),
                    sha256 = sha256Hex("db".encodeToByteArray()),
                    totalBooks = 0,
                    libraryBooks = 0,
                )
            },
        )

        val output = ByteArrayOutputStream()
        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        val settings = Json.parseToJsonElement(
            readZip(output.toByteArray()).getValue("settings.json").decodeToString(),
        ).jsonObject
        assertEquals("", settings.getValue("backupDirectory").jsonPrimitive.content)
        assertEquals("", settings.getValue("exportDirectory").jsonPrimitive.content)
        assertEquals("key", settings.getValue("openAiKeys").jsonPrimitive.content)
    }

    @Test
    fun `reader preferences and assets are included with hashes`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "reader/fonts/custom.ttf", "font-bytes")
        write(filesDir, "reader/backgrounds/reader-bg.jpg", "reader-bg")
        write(filesDir, "backgrounds/theme-bg.jpg", "theme-bg")
        write(filesDir, "preset_backgrounds/1_Backgrounds.jpeg", "preset-bytes")
        write(filesDir, "reader/fonts/stale.ttf.tmp", "temp")
        write(cacheDir, "bg_presets/cached.jpg", "cache")
        val prefs = ReaderBackupPrefs(
            theme = mapOf("mode" to "Custom", "custom_bg" to "/files/backgrounds/theme-bg.jpg"),
            readerConfig = mapOf("mode" to "Paged"),
            tts = mapOf("session" to "v=1&book=book"),
        )
        val service = requiredService(
            root,
            filesDir,
            cacheDir,
            readerSnapshot = { prefs },
        )
        val output = ByteArrayOutputStream()

        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        assertEquals(3, result.readerFileCount)
        val entries = readZip(output.toByteArray())
        val reader = BackupJson.decodeFromString(
            ReaderBackupPrefs.serializer(),
            entries.getValue("reader.json").decodeToString(),
        )
        assertEquals(prefs, reader)
        assertEquals("font-bytes", entries.getValue("reader/assets/fonts/custom.ttf").decodeToString())
        assertEquals(
            "reader-bg",
            entries.getValue("reader/assets/reader-backgrounds/reader-bg.jpg").decodeToString(),
        )
        assertEquals(
            "theme-bg",
            entries.getValue("reader/assets/theme-backgrounds/theme-bg.jpg").decodeToString(),
        )
        assertFalse(entries.containsKey("reader/assets/fonts/stale.ttf.tmp"))
        assertFalse(entries.keys.any { it.contains("bg_presets") })
        // Ready-made presets are re-downloadable and must stay out of backups;
        // user backgrounds stay in.
        assertFalse(entries.keys.any { it.contains("preset_backgrounds") })

        val manifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            entries.getValue("manifest.json").decodeToString(),
        )
        assertTrue(manifest.readerIncluded)
        assertEquals(3, manifest.counts.readerFiles)
        assertEquals(sha256Hex(entries.getValue("reader.json")), manifest.reader?.sha256)
        manifest.readerAssets.forEach { asset ->
            assertEquals(asset.sha256, sha256Hex(entries.getValue(asset.path)))
            assertEquals(asset.size, entries.getValue(asset.path).size.toLong())
        }
    }

    @Test
    fun `reader payload is omitted when settings are off`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "reader/fonts/custom.ttf", "font-bytes")
        val service = requiredService(
            root,
            filesDir,
            cacheDir,
            readerSnapshot = { ReaderBackupPrefs(readerConfig = mapOf("mode" to "Paged")) },
        )
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includeSettings = false),
        )

        assertTrue(result.error, result.success)
        assertEquals(0, result.readerFileCount)
        val entries = readZip(output.toByteArray())
        assertFalse(entries.containsKey("reader.json"))
        assertFalse(entries.keys.any { it.startsWith("reader/assets/") })
        val manifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            entries.getValue("manifest.json").decodeToString(),
        )
        assertFalse(manifest.readerIncluded)
        assertEquals(null, manifest.reader)
        assertTrue(manifest.readerAssets.isEmpty())
    }

    @Test
    fun `sha256File hashes the whole file`() {
        val root = tempDir()
        val file = write(root, "hash.bin", "hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256File(file),
        )
    }

    @Test
    fun `failed snapshot returns an error and leaves no temp database`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        var target: File? = null
        val service = BackupService(
            databasePath = File(root, "live.db"),
            filesDir = filesDir,
            cacheDir = cacheDir,
            appVersion = "test",
            localBooks = { emptyList() },
            pluginKvSnapshot = { PluginKvBackup() },
            localSourcesSnapshot = { LocalSourcesBackup() },
            snapshotDatabase = { file, _ ->
                target = file
                error("snapshot failed")
            },
        )

        val result = service.createBackup(ByteArrayOutputStream())

        assertFalse(result.success)
        assertNotNull(result.error)
        assertFalse(target!!.exists())
    }

    @Test
    fun `plugin cover is included when images are enabled`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val cover = write(filesDir, "covers/42/cover", "plugin-cover")
        val covers = listOf(
            LocalBackupCover(
                bookId = 42,
                installId = "plugin-one",
                title = "Plugin",
                coverLocalPath = cover.absolutePath,
                isUserOverride = false,
            ),
        )
        val service = requiredService(root, filesDir, cacheDir, covers = covers)
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includeSettings = false, includeImages = true, includePlugins = false),
        )

        assertTrue(result.error, result.success)
        val entries = readZip(output.toByteArray())
        assertEquals("plugin-cover", entries.getValue("files/covers/42/cover").decodeToString())
    }

    @Test
    fun `user override cover is included even when images are disabled`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val cover = write(filesDir, "covers/43/cover", "user-cover")
        val covers = listOf(
            LocalBackupCover(
                bookId = 43,
                installId = "plugin-one",
                title = "Plugin",
                coverLocalPath = cover.absolutePath,
                isUserOverride = true,
            ),
        )
        val service = requiredService(root, filesDir, cacheDir, covers = covers)
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includeSettings = false, includeImages = false, includePlugins = false),
        )

        assertTrue(result.error, result.success)
        val entries = readZip(output.toByteArray())
        assertEquals("user-cover", entries.getValue("files/covers/43/cover").decodeToString())
    }

    @Test
    fun `plugin cached cover is skipped when images are disabled`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val cover = write(filesDir, "covers/44/cover", "cached-cover")
        val covers = listOf(
            LocalBackupCover(
                bookId = 44,
                installId = "plugin-one",
                title = "Plugin",
                coverLocalPath = cover.absolutePath,
                isUserOverride = false,
            ),
        )
        val service = requiredService(root, filesDir, cacheDir, covers = covers)
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includeSettings = false, includeImages = false, includePlugins = false),
        )

        assertTrue(result.error, result.success)
        assertFalse(readZip(output.toByteArray()).containsKey("files/covers/44/cover"))
    }

    @Test
    fun `manifest reports total books and the in-library subset`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val service = requiredService(
            root,
            filesDir,
            cacheDir,
            snapshotBookCount = 12,
            snapshotLibraryCount = 7,
        )
        val output = ByteArrayOutputStream()

        val libraryOnly = service.createBackup(
            output,
            BackupOptions(
                includeSettings = false,
                includeImages = false,
                includePlugins = false,
                includeHistoryOnly = false,
            ),
        )

        assertTrue(libraryOnly.error, libraryOnly.success)
        assertEquals(7, libraryOnly.bookCount)
        assertEquals(7, libraryOnly.libraryBookCount)
        assertEquals(0, libraryOnly.localBookCount)
        val libraryManifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            readZip(output.toByteArray()).getValue("manifest.json").decodeToString(),
        )
        assertEquals(7, libraryManifest.counts.totalBooks)
        assertEquals(7, libraryManifest.counts.libraryBooks)
        assertEquals(0, libraryManifest.counts.localBooks)

        val withHistoryOutput = ByteArrayOutputStream()
        val withHistory = service.createBackup(
            withHistoryOutput,
            BackupOptions(
                includeSettings = false,
                includeImages = false,
                includePlugins = false,
                includeHistoryOnly = true,
            ),
        )
        assertEquals(12, withHistory.bookCount)
        assertEquals(7, withHistory.libraryBookCount)
        val fullManifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            readZip(withHistoryOutput.toByteArray()).getValue("manifest.json").decodeToString(),
        )
        assertEquals(12, fullManifest.counts.totalBooks)
        assertEquals(7, fullManifest.counts.libraryBooks)
    }

    @Test
    fun `snapshot receives the includeHistoryOnly option`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val seen = mutableListOf<Boolean>()
        val service = requiredService(
            root,
            filesDir,
            cacheDir,
            seenHistoryFlags = seen,
        )

        service.createBackup(
            ByteArrayOutputStream(),
            BackupOptions(includeSettings = false, includeHistoryOnly = true),
        )
        service.createBackup(
            ByteArrayOutputStream(),
            BackupOptions(includeSettings = false, includeHistoryOnly = false),
        )

        assertEquals(listOf(true, false), seen)
    }

    private fun requiredService(
        root: File,
        filesDir: File,
        cacheDir: File,
        books: List<LocalBackupBook> = emptyList(),
        covers: List<LocalBackupCover> = emptyList(),
        snapshotBookCount: Int = 0,
        snapshotLibraryCount: Int = 0,
        seenHistoryFlags: MutableList<Boolean>? = null,
        readerSnapshot: () -> ReaderBackupPrefs = { ReaderBackupPrefs() },
    ) = BackupService(
        databasePath = File(root, "live.db"),
        filesDir = filesDir,
        cacheDir = cacheDir,
        appVersion = "test",
        localBooks = { books },
        localCovers = { covers },
        readerSnapshot = readerSnapshot,
        pluginKvSnapshot = { PluginKvBackup() },
        localSourcesSnapshot = { LocalSourcesBackup() },
        snapshotDatabase = { target, includeHistoryOnly ->
            seenHistoryFlags?.add(includeHistoryOnly)
            target.writeText("db")
            DatabaseSnapshot(
                file = target,
                size = target.length(),
                sha256 = sha256Hex("db".encodeToByteArray()),
                totalBooks = snapshotBookCount,
                libraryBooks = snapshotLibraryCount,
            )
        },
    )

    @Test
    fun `options omit settings and cache images but keep local content`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "local_books/book.epub", "copy")
        write(filesDir, "local_books/book-body.html", "body")
        write(filesDir, "local_books/book-page.jpg", "local-page")
        write(filesDir, "covers/1/cover", "local-cover")
        write(filesDir, "local_books/manga/folder/001.jpg", "manga-copy")
        write(filesDir, "remote/pages/3.jpg", "remote-page")
        write(filesDir, "covers/3/cover", "remote-cover")
        write(filesDir, "mirutime_plugins/lua_plugins/a.lua", "lua")
        val books = listOf(
            LocalBackupBook(
                bookId = 1,
                installId = "local-books",
                title = "Book",
                inLibrary = true,
                copyPath = "local_books/book.epub",
                format = "EPUB",
                coverLocalPath = "covers/1/cover",
                bodyPaths = listOf("local_books/book-body.html"),
            ),
            LocalBackupBook(
                bookId = 2,
                installId = "local-manga",
                title = "Manga folder",
                inLibrary = true,
                copyPath = "local_books/manga/folder",
                format = "CBZ",
                coverLocalPath = null,
                bodyPaths = emptyList(),
            ),
            LocalBackupBook(
                bookId = 3,
                installId = "plugin-one",
                title = "Remote cache",
                inLibrary = true,
                copyPath = null,
                format = "EPUB",
                coverLocalPath = "covers/3/cover",
                bodyPaths = emptyList(),
            ),
        )
        val service = requiredService(root, filesDir, cacheDir, books)
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includeSettings = false, includeImages = false, includePlugins = false),
        )

        assertTrue(result.error, result.success)
        assertEquals(2, result.localFileCount)
        assertEquals(0, result.pluginFileCount)
        val entries = readZip(output.toByteArray())
        assertEquals(
            setOf(
                "manifest.json",
                "database/mirutime.db",
                "files/local_books/book.epub",
                "files/local_books/book-body.html",
            ),
            entries.keys,
        )
        assertFalse(entries.containsKey("files/local_books/book-page.jpg"))
        assertFalse(entries.containsKey("files/local_books/manga/folder/001.jpg"))
        assertFalse(entries.containsKey("files/covers/1/cover"))
        assertFalse(entries.containsKey("files/covers/3/cover"))
        assertFalse(entries.containsKey("files/remote/pages/3.jpg"))
        val manifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            entries.getValue("manifest.json").decodeToString(),
        )
        assertFalse(manifest.settingsIncluded)
        assertFalse(manifest.imagesIncluded)
        assertFalse(manifest.pluginsIncluded)
        assertEquals(null, manifest.settings)
        assertEquals(2, manifest.files.size)
        manifest.files.forEach { file ->
            assertEquals(file.sha256, sha256Hex(entries.getValue(file.path)))
            assertEquals(file.size, entries.getValue(file.path).size.toLong())
        }
    }

    @Test
    fun `local book with missing copy fails backup with a clear error`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val book = LocalBackupBook(
            bookId = 41,
            installId = "local-books",
            title = "Broken copy",
            inLibrary = true,
            copyPath = "local_books/missing.epub",
            format = "EPUB",
            coverLocalPath = null,
            bodyPaths = emptyList(),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))

        val result = service.createBackup(ByteArrayOutputStream())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("local-copy"))
        assertTrue(result.error.contains("41"))
        assertTrue(result.error.contains("local_books/missing.epub"))
    }

    @Test
    fun `local book with missing body fails backup`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "local_books/book.epub", "copy")
        val book = LocalBackupBook(
            bookId = 42,
            installId = "local-books",
            title = "Broken body",
            inLibrary = true,
            copyPath = "local_books/book.epub",
            format = "EPUB",
            coverLocalPath = null,
            bodyPaths = listOf("local_books/missing.html"),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))

        val result = service.createBackup(ByteArrayOutputStream())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("chapter-body"))
        assertTrue(result.error.contains("42"))
        assertTrue(result.error.contains("local_books/missing.html"))
    }

    @Test
    fun `plugin book cached bodies are packed`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val body = write(filesDir, "downloads/text/1/2.html", "<p>body</p>")
        val book = LocalBackupBook(
            bookId = 45,
            installId = "chikari",
            title = "Plugin book",
            inLibrary = true,
            copyPath = null,
            format = "",
            coverLocalPath = null,
            bodyPaths = listOf(body.absolutePath),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))
        val output = ByteArrayOutputStream()

        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        assertEquals(1, result.localFileCount)
        val entries = readZip(output.toByteArray())
        val hash = sha256Hex("downloads/text/1/2.html".encodeToByteArray())
        val entry = "files/downloads/bodies/$hash.html"
        assertEquals("<p>body</p>", entries.getValue(entry).decodeToString())
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash))
        // The id-based cache path would collide with another book's file after
        // restoring into a different library, so it must not be packed as is.
        assertTrue(entries.keys.none { it.startsWith("files/downloads/text/") })
        assertTrue(entries.keys.none { it.startsWith("files/local_books") })
    }

    @Test
    fun `plugin book with a missing cached body still backs up`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val book = LocalBackupBook(
            bookId = 46,
            installId = "chikari",
            title = "Plugin book",
            inLibrary = true,
            copyPath = null,
            format = "",
            coverLocalPath = null,
            bodyPaths = listOf("mirutime_plugins/chikari/bodies/missing.html"),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))
        val output = ByteArrayOutputStream()

        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        assertEquals(0, result.localFileCount)
        assertTrue(readZip(output.toByteArray()).keys.none { it.contains("mirutime_plugins") })
    }

    @Test
    fun `local manga content is never packed`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "local_books/manga/folder/001.jpg", "copy-page")
        val book = LocalBackupBook(
            bookId = 43,
            installId = "local-manga",
            title = "Manga",
            inLibrary = true,
            copyPath = "local_books/manga/folder",
            format = "CBZ",
            coverLocalPath = null,
            bodyPaths = emptyList(),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))
        val output = ByteArrayOutputStream()

        val result = service.createBackup(output)

        assertTrue(result.error, result.success)
        assertEquals(0, result.localFileCount)
        assertTrue(readZip(output.toByteArray()).keys.none { it.contains("manga") })
    }

    @Test
    fun `remote missing cache paths are skipped`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val book = LocalBackupBook(
            bookId = 44,
            installId = "plugin-one",
            title = "Remote",
            inLibrary = true,
            copyPath = null,
            format = "EPUB",
            coverLocalPath = "covers/missing",
            bodyPaths = listOf("remote/missing.html"),
        )
        val service = requiredService(root, filesDir, cacheDir, listOf(book))

        val result = service.createBackup(ByteArrayOutputStream())

        assertTrue(result.error, result.success)
        assertEquals(0, result.localFileCount)
    }

    @Test
    fun `plugin files are included with hashes when requested`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "mirutime_plugins/lua_plugins/source.lua", "plugin")
        write(filesDir, "mirutime_plugins/lua_libs/shared.lua", "lib")
        val service = requiredService(root, filesDir, cacheDir)
        val output = ByteArrayOutputStream()

        val result = service.createBackup(
            output,
            BackupOptions(includePlugins = true),
        )

        assertTrue(result.error, result.success)
        assertEquals(2, result.pluginFileCount)
        val entries = readZip(output.toByteArray())
        assertEquals("plugin", entries.getValue("plugins/lua_plugins/source.lua").decodeToString())
        assertEquals("lib", entries.getValue("plugins/lua_libs/shared.lua").decodeToString())
        val manifest = BackupJson.decodeFromString(
            BackupManifest.serializer(),
            entries.getValue("manifest.json").decodeToString(),
        )
        assertTrue(manifest.pluginsIncluded)
        assertEquals(2, manifest.counts.pluginFiles)
        assertTrue(manifest.plugins.all { it.sha256 == sha256Hex(entries.getValue(it.path)) })
    }

    @Test
    fun `post-write validation rejects truncated and tampered archives`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        write(filesDir, "local_books/book.epub", "copy")
        val service = requiredService(
            root,
            filesDir,
            cacheDir,
            listOf(
                LocalBackupBook(
                    bookId = 1,
                    installId = "local-books",
                    title = "Book",
                    inLibrary = true,
                    copyPath = "local_books/book.epub",
                    format = "EPUB",
                    coverLocalPath = null,
                    bodyPaths = emptyList(),
                ),
            ),
        )
        val output = ByteArrayOutputStream()
        assertTrue(service.createBackup(output).success)
        val bytes = output.toByteArray()
        val archive = File(root, "archive.zip").apply { writeBytes(bytes) }

        validateBackupArchive(archive)

        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val tamperedFile = File(root, "tampered.zip").apply { writeBytes(tampered) }
        assertTrue(runCatching { validateBackupArchive(tamperedFile) }.isFailure)

        val truncated = File(root, "truncated.zip").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        assertTrue(runCatching { validateBackupArchive(truncated) }.isFailure)
    }

    @Test
    fun `stale temp cleanup keeps the caller file`() {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val keep = File(cacheDir, "mirutime-backup-keep.tmp").apply { writeText("keep") }
        val stale = File(cacheDir, "mirutime-backup-stale.tmp").apply { writeText("stale") }
        val service = requiredService(root, filesDir, cacheDir)

        service.cleanupStaleTemps(keep = keep)

        assertTrue(keep.isFile)
        assertFalse(stale.exists())
    }

    @Test
    fun `backup refuses to start while another operation is active`() = runBlocking {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val service = requiredService(root, filesDir, cacheDir)

        assertTrue(BackupOperationGate.tryEnter())
        try {
            val result = service.createBackup(ByteArrayOutputStream())
            assertFalse(result.success)
            assertTrue(result.error!!.contains("already running"))
        } finally {
            BackupOperationGate.exit()
        }
    }

    @Test
    fun `stale database snapshots are cleaned with an age guard and keep`() {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val now = 1_800_000_000_000L
        val old = File(cacheDir, "mirutime-db-old.db").apply { writeText("db") }
        val oldWal = File(cacheDir, "mirutime-db-old.db-wal").apply { writeText("wal") }
        val oldShm = File(cacheDir, "mirutime-db-old.db-shm").apply { writeText("shm") }
        val recent = File(cacheDir, "mirutime-db-recent.db").apply { writeText("db") }
        listOf(old, oldWal, oldShm).forEach {
            it.setLastModified(now - StaleDbTempAgeMs - 1_000L)
        }
        recent.setLastModified(now)
        val service = requiredService(root, filesDir, cacheDir)

        service.cleanupStaleTemps(now = now)

        assertFalse(old.exists())
        assertFalse(oldWal.exists())
        assertFalse(oldShm.exists())
        assertTrue(recent.exists())

        val keep = File(cacheDir, "mirutime-db-keep.db").apply { writeText("db") }
        keep.setLastModified(now - StaleDbTempAgeMs - 1_000L)
        service.cleanupStaleTemps(keep = keep, now = now)
        assertTrue(keep.exists())
    }

    @Test
    fun `operation gate rejects a concurrent start`() {
        assertTrue(BackupOperationGate.tryEnter())
        try {
            assertFalse(BackupOperationGate.tryEnter())
        } finally {
            BackupOperationGate.exit()
        }
        assertTrue(BackupOperationGate.tryEnter())
        BackupOperationGate.exit()
    }

    @Test
    fun `free space check rejects an impossible size`() {
        val root = tempDir()
        assertTrue(
            runCatching {
                requireFreeSpace(root, Long.MAX_VALUE / 2, "test")
            }.isFailure,
        )
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-backup-test").toFile().also { tempDirs += it }

    private fun write(root: File, relative: String, text: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }
}
