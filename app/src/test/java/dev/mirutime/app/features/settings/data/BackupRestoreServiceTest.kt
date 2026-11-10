package dev.mirutime.app.features.settings.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.mirutime.data.library.LibraryImportReport
import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.MinimumRestorableBackupSchemaVersion
import dev.mirutime.data.library.inspectBackupDatabase
import dev.mirutime.data.library.migrateBackupDatabase
import dev.mirutime.data.library.verifyDatabaseSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreServiceTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `valid archive restores live settings and files without restart`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("old-copy")
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        val settings = PersistedSettings(provider = "OPENAI", openAiKeys = "secret-key")
        val archive = archive(
            settings = settings,
            files = listOf(fileEntry("files/local_books/book.epub", "new-copy")),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals("secret-key", result.settings?.openAiKeys)
        assertEquals("new-copy", env.file("local_books/book.epub").readText())
        assertEquals("secret-key", env.settingsStore.load().openAiKeys)
        assertFalse(env.stagingRoot.exists())
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `schema mismatch is rejected and leaves no staging`() {
        val env = environment()
        val archive = archive(schemaVersion = LibrarySchema)
            .withManifest { it.copy(schemaVersion = 99) }

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("schema"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `v6 backup is migrated to v7 in staging before import`() {
        val env = environment()
        val v6 = v6Database(env.root)
        val archive = archive(database = v6.readBytes(), schemaVersion = 6)
        var importedVersion = -1
        var importedBooks = -1
        var importedTranslations = -1
        env.service = env.serviceWithImport(
            importDatabase = { source, _, _, _, _ ->
                BundledSQLiteDriver().open(source.absolutePath).use { connection ->
                    importedVersion = connection.int("PRAGMA user_version")
                    importedBooks = connection.int("SELECT COUNT(*) FROM books")
                    importedTranslations =
                        connection.int("SELECT COUNT(*) FROM chapter_translations")
                }
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            verifyDatabase = { verifyDatabaseSnapshot(it.absolutePath) },
            inspectDatabase = { inspectBackupDatabase(it.absolutePath) },
            migrateDatabase = { file, from ->
                migrateBackupDatabase(file.absolutePath, from, LibrarySchemaVersion)
            },
            maxEntryBytes = 4L * 1024 * 1024,
            maxTotalBytes = 16L * 1024 * 1024,
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(LibrarySchemaVersion, importedVersion)
        assertEquals(1, importedBooks)
        assertEquals(1, importedTranslations)
        assertNoStagingLeft(env)
    }

    @Test
    fun `v5 backup is rejected as too old and leaves the live state intact`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("live-copy")
        env.settingsStore.save(PersistedSettings(openAiKeys = "live-key"))
        val archive = archive(schemaVersion = MinimumRestorableBackupSchemaVersion - 1)

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("schema 5"))
        assertTrue(result.error.contains("too old"))
        assertEquals("live-copy", env.file("local_books/book.epub").readText())
        assertEquals("live-key", env.settingsStore.load().openAiKeys)
        assertNoStagingLeft(env)
    }

    @Test
    fun `failed v6 migration aborts before import and keeps the live state`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("live-copy")
        env.settingsStore.save(PersistedSettings(openAiKeys = "live-key"))
        env.databasePath.parentFile?.mkdirs()
        env.databasePath.writeText("live-database")
        val corrupt = File(env.root, "corrupt-v6.db")
        BundledSQLiteDriver().open(corrupt.absolutePath).use { connection ->
            connection.exec("PRAGMA user_version = 6")
        }
        var imported = false
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                imported = true
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            verifyDatabase = { verifyDatabaseSnapshot(it.absolutePath) },
            inspectDatabase = { inspectBackupDatabase(it.absolutePath) },
            migrateDatabase = { file, from ->
                migrateBackupDatabase(file.absolutePath, from, LibrarySchemaVersion)
            },
            maxEntryBytes = 4L * 1024 * 1024,
            maxTotalBytes = 16L * 1024 * 1024,
        )

        val result = env.service.restoreNow(
            archive(database = corrupt.readBytes(), schemaVersion = 6).inputStream(),
        )

        assertFalse(result.applied)
        assertFalse("import must not run after a failed migration", imported)
        assertTrue(result.error, result.error!!.contains("migration"))
        assertEquals("live-copy", env.file("local_books/book.epub").readText())
        assertEquals("live-key", env.settingsStore.load().openAiKeys)
        assertEquals("live-database", env.databasePath.readText())
        assertNoStagingLeft(env)
    }

    @Test
    fun `v7 backup never invokes the migration`() {
        val env = environment()
        val migrated = mutableListOf<Int>()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            inspectDatabase = { LibrarySchemaVersion },
            migrateDatabase = { _, from -> migrated += from },
        )

        val result = env.service.restoreNow(archive().inputStream())

        assertTrue(result.error, result.applied)
        assertTrue(migrated.isEmpty())
    }

    @Test
    fun `format version mismatch is rejected`() {
        val env = environment()
        val archive = archive().withManifest { it.copy(formatVersion = BackupFormatVersion + 1) }

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("format"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `hash mismatch is rejected`() {
        val env = environment()
        val archive = archive(
            files = listOf(fileEntry("files/book.epub", "real-bytes")),
            tamper = mapOf("files/book.epub" to "fake-bytes".encodeToByteArray()),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error, result.error!!.contains("checksum"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `declared size mismatch is rejected`() {
        val env = environment()
        val archive = archive(
            files = listOf(fileEntry("files/book.epub", "copy", declaredSize = 999)),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("size"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `path traversal and absolute paths are rejected`() {
        listOf("files/../escape.epub", "/data/escape.epub", "files/./escape.epub").forEach { path ->
            val env = environment()
            val archive = archive(files = listOf(fileEntry(path, "escape")))

            val result = env.service.restoreNow(archive.inputStream())

            assertFalse(path, result.applied)
            assertFalse(File(env.root, "escape.epub").exists())
            assertNoStagingLeft(env)
        }
    }

    @Test
    fun `per-entry and total caps are enforced`() {
        val env = environment(maxEntryBytes = 8, maxTotalBytes = 100_000)
        val large = archive(files = listOf(fileEntry("files/large.bin", "0123456789")))
        val oversized = env.service.restoreNow(large.inputStream())
        assertFalse(oversized.applied)
        assertTrue(oversized.error!!.contains("too large"))
        assertNoStagingLeft(env)

        val small = environment(maxEntryBytes = 64, maxTotalBytes = 20)
        val total = archive(files = listOf(fileEntry("files/a.bin", "0123456789")))
        val result = small.service.restoreNow(total.inputStream())
        assertFalse(result.applied)
        assertTrue(result.error!!.contains("size"))
        assertNoStagingLeft(small)
    }

    @Test
    fun `duplicate entries are rejected`() {
        val env = environment()
        val fixture = archive(files = listOf(fileEntry("files/book.epub", "copy")))
        val archive = fixture.withManifest { it.copy(files = it.files + it.files) }

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error, result.error!!.contains("duplicate"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `unexpected entry is rejected`() {
        val env = environment()
        val archive = archive(
            extra = listOf("files/not-in-manifest.txt" to "x".encodeToByteArray()),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("Unexpected entry"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `malformed settings never leak the payload in the error`() {
        val env = environment()
        val archive = archive(
            settingsBytes = """{"openAiKeys":"super-secret"""".encodeToByteArray(),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("settings.json"))
        assertFalse(result.error.contains("super-secret"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `restore refuses to start while another operation is active`() {
        val env = environment()
        val archive = archive()
        assertTrue(BackupOperationGate.tryEnter())
        try {
            val result = env.service.restoreNow(archive.inputStream())
            assertFalse(result.applied)
            assertTrue(result.error!!.contains("already running"))
        } finally {
            BackupOperationGate.exit()
        }
    }

    @Test
    fun `failed live import rolls back copied files and settings`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("old-copy")
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                error("database write failed")
            },
        )
        val archive = archive(
            settings = PersistedSettings(openAiKeys = "new-key"),
            files = listOf(fileEntry("files/local_books/book.epub", "new-copy")),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertEquals("old-copy", env.file("local_books/book.epub").readText())
        assertEquals("old-key", env.settingsStore.load().openAiKeys)
        assertFalse(env.stagingRoot.exists())
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `failed import before DB commit leaves no staged files`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("old-copy")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                error("database write failed")
            },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "new-copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertEquals("old-copy", env.file("local_books/book.epub").readText())
        assertFalse(env.stagingRoot.exists())
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `new files are removed when the live import fails`() {
        val env = environment()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                error("database write failed")
            },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/new.epub", "new")))

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertFalse(env.file("local_books/new.epub").exists())
    }

    @Test
    fun `interrupted file phase restores previous files and settings`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("old-copy")
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        env.file("local_books/book.epub").writeText("partial-copy")
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files\n42")
        File(env.rollbackRoot, "files/local_books").mkdirs()
        File(env.rollbackRoot, "files/local_books/book.epub").writeText("old-copy")
        File(env.rollbackRoot, "settings.previous.json").writeText(
            BackupJson.encodeToString(
                PersistedSettings.serializer(),
                PersistedSettings(openAiKeys = "old-key"),
            ),
        )

        val result = runBlocking { env.service.recoverInterruptedRestore() }

        assertFalse(result.applied)
        assertEquals("old-copy", env.file("local_books/book.epub").readText())
        assertEquals("old-key", env.settingsStore.load().openAiKeys)
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `journal present without db marker keeps restored files and settings`() {
        val env = environment()
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        env.file("local_books/book.epub").writeText("restored-copy")
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files\n77")
        File(env.rollbackRoot, "settings.restore.json").writeText(
            BackupJson.encodeToString(
                PersistedSettings.serializer(),
                PersistedSettings(openAiKeys = "new-key"),
            ),
        )
        val deleted = mutableListOf<Long>()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ -> LibraryImportReport(bookIdMap = mapOf(1L to 1L)) },
            journalExists = { it == 77L },
            deleteJournal = { deleted += it },
        )

        val result = runBlocking { env.service.recoverInterruptedRestore() }

        assertTrue(result.applied)
        assertEquals("restored-copy", env.file("local_books/book.epub").readText())
        assertEquals("new-key", env.settingsStore.load().openAiKeys)
        assertEquals(listOf(77L), deleted)
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `journal absent without db marker rolls files and settings back`() {
        val env = environment()
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        env.file("local_books/book.epub").writeText("partial-copy")
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files\n88")
        File(env.rollbackRoot, "files/local_books").mkdirs()
        File(env.rollbackRoot, "files/local_books/book.epub").writeText("old-copy")
        File(env.rollbackRoot, "settings.previous.json").writeText(
            BackupJson.encodeToString(
                PersistedSettings.serializer(),
                PersistedSettings(openAiKeys = "old-key"),
            ),
        )

        val result = runBlocking { env.service.recoverInterruptedRestore() }

        assertFalse(result.applied)
        assertEquals("old-copy", env.file("local_books/book.epub").readText())
        assertEquals("old-key", env.settingsStore.load().openAiKeys)
    }

    @Test
    fun `interrupted committed database phase applies restored settings`() {
        val env = environment()
        env.settingsStore.save(PersistedSettings(openAiKeys = "old-key"))
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db\n42")
        File(env.rollbackRoot, "settings.restore.json").writeText(
            BackupJson.encodeToString(
                PersistedSettings.serializer(),
                PersistedSettings(openAiKeys = "new-key"),
            ),
        )

        val result = runBlocking { env.service.recoverInterruptedRestore() }

        assertTrue(result.applied)
        assertEquals("new-key", env.settingsStore.load().openAiKeys)
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `recovery early return applies plugin kv without scanning plugins`() = runBlocking {
        val env = environment()
        env.stagingRoot.mkdirs()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db\n42")
        val kv = PluginKvBackup(mapOf("repo:url" to "https://new.example"))
        File(env.rollbackRoot, "plugin-kv.restore.json").writeText(
            BackupJson.encodeToString(PluginKvBackup.serializer(), kv),
        )
        val store = RecordingPluginKvStore()
        var reloads = 0
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = store,
            reloadPlugins = { reloads += 1 },
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.applied)
        assertEquals(kv, store.restored)
        assertEquals(0, reloads)
    }

    @Test
    fun `recovery post-commit applies and reloads plugin kv`() = runBlocking {
        val env = environment()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db\n42")
        val kv = PluginKvBackup(mapOf("repo:url" to "https://new.example"))
        File(env.rollbackRoot, "plugin-kv.restore.json").writeText(
            BackupJson.encodeToString(PluginKvBackup.serializer(), kv),
        )
        val store = RecordingPluginKvStore()
        var reloads = 0
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = store,
            reloadPlugins = { reloads += 1 },
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.applied)
        assertEquals(kv, store.restored)
        assertEquals(1, reloads)
        assertNull(result.error)
    }

    @Test
    fun `recovery rethrows cancellation`() = runBlocking {
        val env = environment()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db\n42")
        File(env.rollbackRoot, "plugin-kv.restore.json").writeText(
            BackupJson.encodeToString(
                PluginKvBackup.serializer(),
                PluginKvBackup(mapOf("repo:url" to "https://new.example")),
            ),
        )
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = RecordingPluginKvStore(),
            reloadPlugins = { throw CancellationException("cancelled") },
        )

        val thrown = runCatching { env.service.recoverInterruptedRestore() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `recovery continues after a failing kv step`() = runBlocking {
        val env = environment()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db\n42")
        File(env.rollbackRoot, "plugin-kv.restore.json").writeText(
            BackupJson.encodeToString(
                PluginKvBackup.serializer(),
                PluginKvBackup(mapOf("repo:url" to "https://new.example")),
            ),
        )
        val sources = LocalSourcesBackup(books = listOf("content://tree/books"))
        File(env.rollbackRoot, "local-sources.restore.json").writeText(
            BackupJson.encodeToString(LocalSourcesBackup.serializer(), sources),
        )
        val localStore = RecordingLocalSourcesStore()
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = object : PluginKvBackupStore {
                override fun snapshot(): PluginKvBackup = PluginKvBackup()
                override fun restore(backup: PluginKvBackup) = error("kv failed")
            },
            localSourcesStore = localStore,
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.applied)
        assertEquals(sources, localStore.restored)
        assertTrue(result.error!!.contains("kv"))
    }

    @Test
    fun `cancellation during post-commit work keeps staged files`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("old-copy")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, onCommitted ->
                onCommitted(LibraryImportReport(bookIdMap = mapOf(1L to 1L)))
                throw CancellationException("cancelled after commit")
            },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "new-copy")))

        var cancelled = false
        try {
            env.service.restoreNow(archive.inputStream())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        // The DB is durable: the old file must not be rolled back and the
        // staged restore must stay for recovery.
        assertEquals("old-copy", env.file("local_books/book.epub").readText())
        assertTrue(env.stagingRoot.exists())
        assertTrue(env.rollbackRoot.exists())
    }

    @Test
    fun `restore prunes missing manga rows after commit`() {
        val env = environment()
        var pruned = false
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, onCommitted ->
                onCommitted(LibraryImportReport(bookIdMap = mapOf(1L to 1L)))
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            pruneMissingRestoredContent = { pruned = true },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertTrue("manga prune must run after the DB commit", pruned)
    }

    @Test
    fun `recovery prunes missing manga rows when the DB is committed`() = runBlocking {
        val env = environment()
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "new-copy")))
        assertTrue(env.service.stage(archive.inputStream()).success)
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db-files\n42")
        File(env.rollbackRoot, "files.journal").writeText("v1\nb 1 1")
        var pruned = false
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            pruneMissingRestoredContent = { pruned = true },
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.applied)
        assertTrue("recovery must prune missing manga rows", pruned)
    }

    @Test
    fun `normal restore deletes the journal before cleanup`() {
        val env = environment()
        val deleted = mutableListOf<Long>()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            deleteJournal = { deleted += it },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(listOf(42L), deleted)
        assertFalse(env.rollbackRoot.exists())
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `cancellation after database commit still reports applied`() {
        val env = environment()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            deleteJournal = { throw CancellationException("cancelled in tail") },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.applied)
    }

    @Test
    fun `recovery resumes db-files phase and copies staged files`() = runBlocking {
        val env = environment()
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "new-copy")))
        val staged = env.service.stage(archive.inputStream())
        assertTrue(staged.success)

        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db-files\n42")
        File(env.rollbackRoot, "files.journal").writeText("v1\nb 1 1")

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.applied)
        assertEquals("new-copy", env.file("local_books/book.epub").readText())
        assertFalse(env.stagingRoot.exists())
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `failed recovery rollback keeps staging and rollback for next start`() {
        val env = environment()
        env.file("local_books/book.epub").writeText("partial-copy")
        env.stagingRoot.mkdirs()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files\n123")
        File(env.rollbackRoot, "files/local_books").mkdirs()
        File(env.rollbackRoot, "files/local_books/book.epub").writeText("old-copy")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            movePath = { _, _ -> error("rollback disk failure") },
        )

        val result = runBlocking { env.service.recoverInterruptedRestore() }

        assertFalse(result.applied)
        assertTrue(env.rollbackRoot.exists())
        assertTrue(env.stagingRoot.exists())
    }

    @Test
    fun `restore works when settings are not included`() {
        val env = environment()
        env.settingsStore.save(PersistedSettings(openAiKeys = "keep-key"))
        val archive = archive(includeSettings = false)

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals("keep-key", env.settingsStore.load().openAiKeys)
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `restore keeps the device's own backup folders`() {
        val env = environment()
        env.settingsStore.save(
            PersistedSettings(
                backupDirectory = "content://tree/device-backup",
                exportDirectory = "content://tree/device-export",
            ),
        )
        val archive = archive(
            settings = PersistedSettings(
                openAiKeys = "new-key",
                backupDirectory = "content://tree/archive-backup",
                exportDirectory = "content://tree/archive-export",
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        val stored = env.settingsStore.load()
        assertEquals("new-key", stored.openAiKeys)
        assertEquals("content://tree/device-backup", stored.backupDirectory)
        assertEquals("content://tree/device-export", stored.exportDirectory)
    }

    @Test
    fun `leftover staging does not block the next restore`() {
        val env = environment()
        env.file("restore_pending/stale").writeText("leftover")
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals("copy", env.file("local_books/book.epub").readText())
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `unresumable staging with a committed marker does not block the next restore`() {
        val env = environment()
        env.stagingRoot.mkdirs()
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("db-files\n42")
        // No files.journal: the file phase cannot be resumed from this stage.
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals("copy", env.file("local_books/book.epub").readText())
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `journal write failure after DB commit still reports applied`() {
        val env = environment()
        var journalAttempts = 0
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, onCommitted ->
                onCommitted(LibraryImportReport(bookIdMap = mapOf(1L to 1L)))
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            writeAtomicFile = { file, text ->
                if (file.name == "files.journal" && journalAttempts++ == 0) {
                    error("disk full")
                }
                writeTextAtomic(file, text)
            },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.applied)
        assertEquals("disk full", result.error)
        assertEquals("copy", env.file("local_books/book.epub").readText())
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `journal write failure on every attempt still copies the files`() {
        val env = environment()
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, onCommitted ->
                onCommitted(LibraryImportReport(bookIdMap = mapOf(1L to 1L)))
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            // Both journal attempts fail: the copy must still run from the
            // in-memory id map instead of leaving the book without its file.
            writeAtomicFile = { file, text ->
                if (file.name == "files.journal") error("disk full")
                writeTextAtomic(file, text)
            },
        )
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.applied)
        assertEquals("disk full", result.error)
        assertEquals("copy", env.file("local_books/book.epub").readText())
    }

    @Test
    fun `recovery copies staged files when the journal was never written`() = runBlocking {
        val env = environment()
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "new-copy")))
        assertTrue(env.service.stage(archive.inputStream()).success)
        // Crash between the DB commit and the journal write: the marker still
        // says files-copied and no mapping exists on disk.
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files-copied\n42")
        var pruned = false
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            journalExists = { true },
            pruneMissingRestoredContent = { pruned = true },
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.error, result.applied)
        assertEquals("new-copy", env.file("local_books/book.epub").readText())
        assertTrue("covers stay missing, the prune must run", pruned)
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `recovery restores covers when the journal was never written`() = runBlocking {
        val env = environment()
        val archive = archive(
            files = listOf(
                fileEntry("files/covers/1/cover", "stored-cover", kind = "cover", bookId = 1),
                fileEntry("files/local_books/book.epub", "new-copy"),
            ),
        )
        assertTrue(env.service.stage(archive.inputStream()).success)
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files-copied\n42")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ -> LibraryImportReport(bookIdMap = mapOf(1L to 7L)) },
            journalExists = { true },
            restoreBookIdMap = { _, _ -> mapOf(1L to 7L) },
        )

        val result = env.service.recoverInterruptedRestore()

        assertTrue(result.error, result.applied)
        assertEquals("new-copy", env.file("local_books/book.epub").readText())
        // The cover target carries the new book id; the identity join recovers it.
        assertEquals("stored-cover", env.file("covers/7/cover").readText())
        assertFalse(env.stagingRoot.exists())
    }

    @Test
    fun `recovery keeps a cover that already lives on the device`() = runBlocking {
        val env = environment()
        env.file("covers/7/cover").writeText("user-override")
        val archive = archive(
            files = listOf(fileEntry("files/covers/1/cover", "stored-cover", kind = "cover", bookId = 1)),
        )
        assertTrue(env.service.stage(archive.inputStream()).success)
        env.rollbackRoot.mkdirs()
        File(env.rollbackRoot, "progress").writeText("files-copied\n42")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ -> LibraryImportReport(bookIdMap = mapOf(1L to 7L)) },
            journalExists = { true },
            restoreBookIdMap = { _, _ -> mapOf(1L to 7L) },
        )

        env.service.recoverInterruptedRestore()

        assertEquals("user-override", env.file("covers/7/cover").readText())
    }

    @Test
    fun `restore works when image files are not included`() {
        val env = environment()
        val archive = archive(
            imagesIncluded = false,
            files = listOf(
                fileEntry("files/local_books/book.epub", "copy"),
                fileEntry("files/local_books/manga/001.jpg", "page"),
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals("copy", env.file("local_books/book.epub").readText())
        assertEquals("page", env.file("local_books/manga/001.jpg").readText())
        assertFalse(env.file("covers/1/cover").exists())
    }

    @Test
    fun `plugins are merged, overwritten and reloaded`() {
        val env = environment()
        env.file("mirutime_plugins/lua_plugins/existing.lua").writeText("old")
        env.file("mirutime_plugins/lua_plugins/keep.lua").writeText("keep")
        env.file("mirutime_plugins/lua_libs/shared.lua").writeText("old-lib")
        var reloads = 0
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                LibraryImportReport(bookIdMap = mapOf(1L to 1L))
            },
            reloadPlugins = { reloads++ },
        )
        val archive = archive(
            plugins = listOf(
                ManifestPlugin("plugins/lua_plugins/existing.lua", "new".encodeToByteArray()),
                ManifestPlugin("plugins/lua_plugins/new.lua", "fresh".encodeToByteArray()),
                ManifestPlugin("plugins/lua_libs/shared.lua", "new-lib".encodeToByteArray()),
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(3, result.pluginsRestored)
        assertEquals(1, reloads)
        assertEquals("new", env.file("mirutime_plugins/lua_plugins/existing.lua").readText())
        assertEquals("fresh", env.file("mirutime_plugins/lua_plugins/new.lua").readText())
        assertEquals("new-lib", env.file("mirutime_plugins/lua_libs/shared.lua").readText())
        assertEquals("keep", env.file("mirutime_plugins/lua_plugins/keep.lua").readText())
    }

    @Test
    fun `plugin path traversal and unknown plugin dirs are rejected`() {
        listOf(
            "plugins/lua_plugins/../../escape.lua",
            "plugins/other/escape.lua",
            "files/lua_plugins/escape.lua",
        ).forEach { path ->
            val env = environment()
            val archive = archive(
                plugins = listOf(ManifestPlugin(path, "escape".encodeToByteArray())),
            )

            val result = env.service.restoreNow(archive.inputStream())

            assertFalse(path, result.applied)
            assertFalse(File(env.root, "escape.lua").exists())
            assertNoStagingLeft(env)
        }
    }

    @Test
    fun `failed restore rolls back overwritten plugin files`() {
        val env = environment()
        env.file("mirutime_plugins/lua_plugins/existing.lua").writeText("old")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                error("database write failed")
            },
        )
        val archive = archive(
            plugins = listOf(
                ManifestPlugin("plugins/lua_plugins/existing.lua", "new".encodeToByteArray()),
                ManifestPlugin("plugins/lua_plugins/new.lua", "fresh".encodeToByteArray()),
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertEquals("old", env.file("mirutime_plugins/lua_plugins/existing.lua").readText())
        assertFalse(env.file("mirutime_plugins/lua_plugins/new.lua").exists())
    }

    @Test
    fun `staged progress reports every restore stage`() {
        val env = environment()
        val archive = archive(files = listOf(fileEntry("files/local_books/book.epub", "copy")))
        val stages = mutableSetOf<RestoreStage>()

        val result = runBlocking {
            env.service.restoreWithStages(
                input = archive.inputStream(),
                onStage = { stages += it.stage },
            )
        }

        assertTrue(result.error, result.applied)
        assertEquals(
            setOf(
                RestoreStage.VALIDATING,
                RestoreStage.EXTRACTING,
                RestoreStage.DATABASE,
                RestoreStage.FILES,
                RestoreStage.SETTINGS,
                RestoreStage.CACHE,
            ),
            stages,
        )
    }

    @Test
    fun `reader preferences and assets roundtrip`() {
        val env = environment()
        val prefs = ReaderBackupPrefs(
            theme = mapOf("mode" to "Night", "custom_bg" to "/files/backgrounds/bg.jpg"),
            readerConfig = mapOf("mode" to "Paged", "style" to "{}"),
            tts = mapOf("session" to "v=1&book=book"),
        )
        val archive = archive(
            reader = prefs,
            readerAssets = listOf(
                ManifestReaderAsset("reader/assets/fonts/custom.ttf", "font".encodeToByteArray()),
                ManifestReaderAsset(
                    "reader/assets/theme-backgrounds/bg.jpg",
                    "background".encodeToByteArray(),
                ),
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(prefs, env.readerStore.current)
        assertEquals("font", env.file("reader/fonts/custom.ttf").readText())
        assertEquals("background", env.file("backgrounds/bg.jpg").readText())
        assertFalse(env.stagingRoot.exists())
        assertFalse(env.rollbackRoot.exists())
    }

    @Test
    fun `legacy backup without reader keeps current reader preferences`() {
        val env = environment()
        val current = ReaderBackupPrefs(readerConfig = mapOf("mode" to "Feed"))
        env.readerStore.current = current

        val result = env.service.restoreNow(archive().inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(current, env.readerStore.current)
    }

    @Test
    fun `reader checksum mismatch is rejected`() {
        val env = environment()
        val prefs = ReaderBackupPrefs(readerConfig = mapOf("mode" to "Paged"))
        val bytes = BackupJson
            .encodeToString(ReaderBackupPrefs.serializer(), prefs)
            .encodeToByteArray()
        val tampered = bytes.copyOf().also {
            it[it.size / 2] = (it[it.size / 2] + 1).toByte()
        }
        val archive = archive(reader = prefs, tamper = mapOf(READER_ENTRY to tampered))

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error, result.error!!.contains("checksum"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `unsupported reader preferences version is rejected`() {
        val env = environment()
        val archive = archive(reader = ReaderBackupPrefs(version = 99))

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("reader preferences version"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `reader asset traversal is rejected`() {
        listOf(
            "reader/assets/fonts/../escape.ttf",
            "reader/assets/other/escape.ttf",
            "reader/assets/fonts/./escape.ttf",
        ).forEach { path ->
            val env = environment()
            val archive = archive(
                reader = ReaderBackupPrefs(),
                readerAssets = listOf(
                    ManifestReaderAsset(path, "escape".encodeToByteArray()),
                ),
            )

            val result = env.service.restoreNow(archive.inputStream())

            assertFalse(path, result.applied)
            assertFalse(File(env.root, "escape.ttf").exists())
            assertNoStagingLeft(env)
        }
    }

    @Test
    fun `failed import rolls back reader assets`() {
        val env = environment()
        env.file("reader/fonts/custom.ttf").writeText("old-font")
        env.service = env.serviceWithImport(
            importDatabase = { _, _, _, _, _ ->
                error("database write failed")
            },
        )
        val archive = archive(
            reader = ReaderBackupPrefs(readerConfig = mapOf("mode" to "Paged")),
            readerAssets = listOf(
                ManifestReaderAsset("reader/assets/fonts/custom.ttf", "new-font".encodeToByteArray()),
            ),
        )

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertEquals("old-font", env.file("reader/fonts/custom.ttf").readText())
    }

    @Test
    fun `restore applies plugin kv and local sources`() = runBlocking {
        val env = environment()
        val plugin = PluginKvBackup(mapOf("repo:url" to "https://example.test"))
        val sources = LocalSourcesBackup(
            books = listOf("content://tree/books"),
            registry = mapOf("uri:abc" to "content://tree/book.epub"),
        )
        val pluginBytes = BackupJson
            .encodeToString(PluginKvBackup.serializer(), plugin)
            .encodeToByteArray()
        val sourceBytes = BackupJson
            .encodeToString(LocalSourcesBackup.serializer(), sources)
            .encodeToByteArray()
        val pluginStore = RecordingPluginKvStore()
        val sourcesStore = RecordingLocalSourcesStore()
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = pluginStore,
            localSourcesStore = sourcesStore,
        )
        val archive = archive(
            extra = listOf(
                PLUGIN_KV_ENTRY to pluginBytes,
                LOCAL_SOURCES_ENTRY to sourceBytes,
            ),
        ).withManifest {
            it.copy(
                pluginKv = BackupEntry(PLUGIN_KV_ENTRY, pluginBytes.size.toLong(), sha256Hex(pluginBytes)),
                localSources = BackupEntry(
                    LOCAL_SOURCES_ENTRY,
                    sourceBytes.size.toLong(),
                    sha256Hex(sourceBytes),
                ),
            )
        }

        val result = env.service.restoreNow(archive.inputStream())

        assertTrue(result.error, result.applied)
        assertEquals(plugin, pluginStore.restored)
        assertEquals(sources, sourcesStore.restored)
    }

    @Test
    fun `malformed plugin kv is rejected before the database commit`() = runBlocking {
        val env = environment()
        val bad = "{".encodeToByteArray()
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
        )
        val archive = archive(extra = listOf(PLUGIN_KV_ENTRY to bad)).withManifest {
            it.copy(
                pluginKv = BackupEntry(PLUGIN_KV_ENTRY, bad.size.toLong(), sha256Hex(bad)),
            )
        }

        val result = env.service.restoreNow(archive.inputStream())

        assertFalse(result.applied)
        assertTrue(result.error!!.contains("plugin-kv"))
        assertNoStagingLeft(env)
    }

    @Test
    fun `post-commit plugin kv failure does not block the next restore`() = runBlocking {
        val env = environment()
        val plugin = PluginKvBackup(mapOf("repo:url" to "https://example.test"))
        val bytes = BackupJson
            .encodeToString(PluginKvBackup.serializer(), plugin)
            .encodeToByteArray()
        val archive = archive(
            extra = listOf(PLUGIN_KV_ENTRY to bytes),
        ).withManifest {
            it.copy(
                pluginKv = BackupEntry(PLUGIN_KV_ENTRY, bytes.size.toLong(), sha256Hex(bytes)),
            )
        }
        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
            pluginKvStore = object : PluginKvBackupStore {
                override fun snapshot(): PluginKvBackup = PluginKvBackup()
                override fun restore(backup: PluginKvBackup) = error("kv failed")
            },
        )

        val first = env.service.restoreNow(archive.inputStream())

        assertTrue(first.applied)
        assertTrue(first.error!!.contains("kv failed"))
        assertFalse(env.stagingRoot.exists())

        env.service = service(
            env.root,
            env.filesDir,
            env.databasePath,
            env.settingsStore,
            maxEntryBytes = 4096,
            maxTotalBytes = 8192,
        )
        val second = env.service.restoreNow(archive.inputStream())
        assertTrue(second.error, second.applied)
    }

    private class RecordingPluginKvStore : PluginKvBackupStore {
        var restored: PluginKvBackup? = null
        override fun snapshot(): PluginKvBackup = PluginKvBackup()
        override fun restore(backup: PluginKvBackup) {
            restored = backup
        }
    }

    private class RecordingLocalSourcesStore : LocalSourcesBackupStore {
        var restored: LocalSourcesBackup? = null
        override fun snapshot(): LocalSourcesBackup = LocalSourcesBackup()
        override fun restore(backup: LocalSourcesBackup) {
            restored = backup
        }
    }

    private fun environment(
        maxEntryBytes: Long = 1024,
        maxTotalBytes: Long = 4096,
    ): Env {
        val root = tempDir()
        val filesDir = File(root, "files").apply { mkdirs() }
        val databasePath = File(root, "databases/mirutime.db")
        databasePath.parentFile?.mkdirs()
        val store = MemoryStore()
        val settingsStore = SettingsStore(store)
        val readerStore = FakeReaderStore()
        return Env(
            root = root,
            filesDir = filesDir,
            databasePath = databasePath,
            store = store,
            settingsStore = settingsStore,
            readerStore = readerStore,
            service = service(
                root,
                filesDir,
                databasePath,
                settingsStore,
                maxEntryBytes,
                maxTotalBytes,
                readerStore = readerStore,
            ),
        )
    }

    private fun service(
        root: File,
        filesDir: File,
        databasePath: File,
        settingsStore: SettingsStore,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        readerStore: ReaderBackupStore = NoopReaderBackupStore,
        importDatabase: ImportDatabase = { _, _, _, _, _ ->
            LibraryImportReport(bookIdMap = mapOf(1L to 1L))
        },
        reloadPlugins: suspend () -> Unit = {},
        journalExists: suspend (Long) -> Boolean = { false },
        deleteJournal: suspend (Long) -> Unit = {},
        newOperationId: () -> Long = { 42L },
        deletePath: (File) -> Boolean = { it.delete() },
        movePath: ((File, File) -> Unit)? = null,
        verifyDatabase: (File) -> Unit = {},
        inspectDatabase: (File) -> Int = { LibrarySchema },
        migrateDatabase: (File, Int) -> Unit = { _, _ -> },
        pluginKvStore: PluginKvBackupStore = NoopPluginKvBackupStore,
        localSourcesStore: LocalSourcesBackupStore = NoopLocalSourcesBackupStore,
        pruneMissingRestoredContent: suspend () -> Unit = {},
        writeAtomicFile: (File, String) -> Unit = ::writeTextAtomic,
        restoreBookIdMap: (File, File) -> Map<Long, Long> = { _, _ -> emptyMap() },
    ) = BackupRestoreService(
        filesDir = filesDir,
        databasePath = databasePath,
        settingsStore = settingsStore,
        readerStore = readerStore,
        pluginKvStore = pluginKvStore,
        localSourcesStore = localSourcesStore,
        appDataDir = root,
        verifyDatabase = verifyDatabase,
        inspectDatabase = inspectDatabase,
        migrateDatabase = migrateDatabase,
        importDatabase = importDatabase,
        reloadPlugins = reloadPlugins,
        journalExists = journalExists,
        deleteJournal = deleteJournal,
        newOperationId = newOperationId,
        deletePath = deletePath,
        movePath = movePath,
        writeAtomicFile = writeAtomicFile,
        maxEntryBytes = maxEntryBytes,
        maxTotalBytes = maxTotalBytes,
        pruneMissingRestoredContent = pruneMissingRestoredContent,
        restoreBookIdMap = restoreBookIdMap,
    )

    private fun Env.serviceWithImport(
        importDatabase: ImportDatabase,
        reloadPlugins: suspend () -> Unit = {},
        journalExists: suspend (Long) -> Boolean = { false },
        deleteJournal: suspend (Long) -> Unit = {},
        movePath: ((File, File) -> Unit)? = null,
        readerStore: ReaderBackupStore = this.readerStore,
        verifyDatabase: (File) -> Unit = {},
        inspectDatabase: (File) -> Int = { LibrarySchema },
        migrateDatabase: (File, Int) -> Unit = { _, _ -> },
        maxEntryBytes: Long = 1024,
        maxTotalBytes: Long = 4096,
        pruneMissingRestoredContent: suspend () -> Unit = {},
        writeAtomicFile: (File, String) -> Unit = ::writeTextAtomic,
        restoreBookIdMap: (File, File) -> Map<Long, Long> = { _, _ -> emptyMap() },
    ): BackupRestoreService = service(
        root = root,
        filesDir = filesDir,
        databasePath = databasePath,
        settingsStore = settingsStore,
        readerStore = readerStore,
        maxEntryBytes = maxEntryBytes,
        maxTotalBytes = maxTotalBytes,
        importDatabase = importDatabase,
        reloadPlugins = reloadPlugins,
        journalExists = journalExists,
        deleteJournal = deleteJournal,
        movePath = movePath,
        verifyDatabase = verifyDatabase,
        inspectDatabase = inspectDatabase,
        migrateDatabase = migrateDatabase,
        pruneMissingRestoredContent = pruneMissingRestoredContent,
        writeAtomicFile = writeAtomicFile,
        restoreBookIdMap = restoreBookIdMap,
    )

    private fun archive(
        database: ByteArray = "db".encodeToByteArray(),
        settings: PersistedSettings = PersistedSettings(),
        files: List<ManifestFile> = emptyList(),
        settingsBytes: ByteArray? = null,
        includeSettings: Boolean = true,
        imagesIncluded: Boolean = true,
        plugins: List<ManifestPlugin> = emptyList(),
        pluginsIncluded: Boolean = plugins.isNotEmpty(),
        reader: ReaderBackupPrefs? = null,
        readerAssets: List<ManifestReaderAsset> = emptyList(),
        schemaVersion: Int = LibrarySchema,
        extra: List<Pair<String, ByteArray>> = emptyList(),
        tamper: Map<String, ByteArray> = emptyMap(),
    ): ZipFixture {
        val actualReader = if (!includeSettings) {
            null
        } else {
            reader?.let {
                BackupJson.encodeToString(ReaderBackupPrefs.serializer(), it).encodeToByteArray()
            }
        }
        val actualSettings = if (!includeSettings) {
            null
        } else {
            settingsBytes
                ?: BackupJson.encodeToString(PersistedSettings.serializer(), settings)
                    .encodeToByteArray()
        }
        val manifest = BackupManifest(
            appVersion = "test",
            schemaVersion = schemaVersion,
            createdAt = 0,
            settingsIncluded = includeSettings,
            imagesIncluded = imagesIncluded,
            pluginsIncluded = pluginsIncluded,
            readerIncluded = actualReader != null,
            database = BackupEntry(DATABASE_ENTRY, database.size.toLong(), sha256Hex(database)),
            settings = actualSettings?.let {
                BackupEntry(SETTINGS_ENTRY, it.size.toLong(), sha256Hex(it))
            },
            reader = actualReader?.let {
                BackupEntry(READER_ENTRY, it.size.toLong(), sha256Hex(it))
            },
            readerAssets = readerAssets.map { asset ->
                BackupReaderAssetEntry(
                    path = asset.path,
                    size = asset.bytes.size.toLong(),
                    sha256 = sha256Hex(asset.bytes),
                )
            },
            files = files.map { file ->
                BackupFileEntry(
                    path = file.path,
                    bookId = file.bookId,
                    kind = file.kind,
                    size = file.declaredSize,
                    sha256 = sha256Hex(file.bytes),
                )
            },
            plugins = plugins.map { plugin ->
                BackupPluginEntry(
                    path = plugin.path,
                    size = plugin.declaredSize,
                    sha256 = sha256Hex(plugin.bytes),
                )
            },
            counts = BackupCounts(
                localBooks = 1,
                localFiles = files.size,
                pluginFiles = plugins.size,
                readerFiles = readerAssets.size,
            ),
        )
        return ZipFixture(
            database = database,
            settings = actualSettings,
            reader = actualReader,
            files = files,
            plugins = plugins,
            readerAssets = readerAssets,
            manifest = manifest,
            extra = extra,
            tamper = tamper,
        )
    }

    private fun fileEntry(
        path: String,
        content: String,
        declaredSize: Long = content.encodeToByteArray().size.toLong(),
        kind: String = "local-copy",
        bookId: Long = 1,
    ): ManifestFile = ManifestFile(path, content.encodeToByteArray(), declaredSize, kind, bookId)

    private fun assertNoStagingLeft(env: Env) {
        assertFalse(env.stagingRoot.exists())
        assertTrue(
            env.filesDir.listFiles().orEmpty().none { it.name.startsWith("restore_pending") },
        )
    }

    private data class ManifestFile(
        val path: String,
        val bytes: ByteArray,
        val declaredSize: Long,
        val kind: String = "local-copy",
        val bookId: Long = 1,
    )

    private data class ManifestReaderAsset(
        val path: String,
        val bytes: ByteArray,
    )

    private class FakeReaderStore(
        var current: ReaderBackupPrefs = ReaderBackupPrefs(),
    ) : ReaderBackupStore {
        override fun snapshot(): ReaderBackupPrefs = current

        override fun restore(prefs: ReaderBackupPrefs) {
            current = prefs
        }
    }

    private data class ManifestPlugin(
        val path: String,
        val bytes: ByteArray,
        val declaredSize: Long = bytes.size.toLong(),
    )

    private class Env(
        val root: File,
        val filesDir: File,
        val databasePath: File,
        val store: MemoryStore,
        val settingsStore: SettingsStore,
        val readerStore: FakeReaderStore,
        var service: BackupRestoreService,
    ) {
        val stagingRoot get() = File(filesDir, BackupRestoreService.STAGING_DIR)
        val rollbackRoot get() = File(root, BackupRestoreService.ROLLBACK_DIR)
        fun file(path: String): File = File(filesDir, path).apply { parentFile?.mkdirs() }
    }

    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, Any>()

        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getString(key: String): String? = map[key] as? String
        override fun getInt(key: String): Int? = map[key] as? Int
        override fun getLong(key: String): Long? = map[key] as? Long
        override fun getBoolean(key: String): Boolean? = map[key] as? Boolean

        override fun put(values: Map<String, Any>): Boolean {
            map.putAll(values)
            return true
        }
    }

    private class ZipFixture(
        val database: ByteArray,
        val settings: ByteArray?,
        val reader: ByteArray?,
        val files: List<ManifestFile>,
        val plugins: List<ManifestPlugin>,
        val readerAssets: List<ManifestReaderAsset>,
        val manifest: BackupManifest,
        val extra: List<Pair<String, ByteArray>>,
        val tamper: Map<String, ByteArray>,
    ) {
        fun withManifest(transform: (BackupManifest) -> BackupManifest): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val json = BackupJson
                    .encodeToString(BackupManifest.serializer(), transform(manifest))
                    .encodeToByteArray()
                zip.writeEntry(MANIFEST_ENTRY, json)
                settings?.let { zip.writeEntry(SETTINGS_ENTRY, it) }
                reader?.let { zip.writeEntry(READER_ENTRY, tamper[READER_ENTRY] ?: it) }
                zip.writeEntry(DATABASE_ENTRY, database)
                files.forEach { file ->
                    zip.writeEntry(file.path, tamper[file.path] ?: file.bytes)
                }
                plugins.forEach { plugin ->
                    zip.writeEntry(plugin.path, tamper[plugin.path] ?: plugin.bytes)
                }
                readerAssets.forEach { asset ->
                    zip.writeEntry(asset.path, tamper[asset.path] ?: asset.bytes)
                }
                extra.forEach { (name, bytes) -> zip.writeEntry(name, bytes) }
            }
            return out.toByteArray()
        }

        fun inputStream() = withManifest { it }.inputStream()
    }

    private fun v6Database(root: File): File {
        val file = File(root, "v6-snapshot.db")
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec(
                "CREATE TABLE books (bookId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "url TEXT NOT NULL, title TEXT NOT NULL)",
            )
            connection.exec(
                "CREATE TABLE chapters (chapterId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "bookId INTEGER NOT NULL, idx INTEGER NOT NULL, url TEXT NOT NULL, " +
                    "title TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0, removedAt INTEGER)",
            )
            connection.exec(
                "CREATE TABLE local_book_files (bookId INTEGER PRIMARY KEY NOT NULL, " +
                    "uri TEXT NOT NULL, sha256 TEXT NOT NULL)",
            )
            connection.exec(
                "CREATE TABLE chapter_translations (" +
                    "translationId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "chapterId INTEGER NOT NULL, target TEXT NOT NULL, " +
                    "provider TEXT NOT NULL, model TEXT, promptHash TEXT NOT NULL, " +
                    "sourceHash TEXT NOT NULL, pipelineVersion TEXT NOT NULL, " +
                    "status TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            )
            connection.exec(
                "CREATE TABLE translation_blocks (translationId INTEGER NOT NULL, " +
                    "blockIndex INTEGER NOT NULL, sourceTextHash TEXT NOT NULL, " +
                    "translatedText TEXT NOT NULL, sentenceSpansJson TEXT, " +
                    "PRIMARY KEY(translationId, blockIndex))",
            )
            connection.exec(
                "CREATE TABLE book_translations (" +
                    "translationId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "bookId INTEGER NOT NULL, target TEXT NOT NULL, " +
                    "provider TEXT NOT NULL, model TEXT, promptHash TEXT NOT NULL, " +
                    "sourceHash TEXT NOT NULL, pipelineVersion TEXT NOT NULL, title TEXT, " +
                    "description TEXT, altTitlesJson TEXT NOT NULL, status TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)",
            )
            connection.exec(
                "CREATE INDEX index_chapter_translations_chapterId " +
                    "ON chapter_translations (chapterId)",
            )
            connection.exec(
                "CREATE INDEX index_book_translations_bookId ON book_translations (bookId)",
            )
            connection.exec(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            connection.exec(
                "INSERT INTO room_master_table VALUES (42, " +
                    "'9403073cdde554577f01df02440a66c6')",
            )
            connection.exec("INSERT INTO books (url, title) VALUES ('local://v6', 'V6 Book')")
            connection.exec(
                "INSERT INTO chapters (bookId, idx, url, title) " +
                    "VALUES (1, 1, 'local://v6#1', 'One')",
            )
            connection.exec(
                """
                INSERT INTO chapter_translations
                    (chapterId, target, provider, model, promptHash, sourceHash,
                     pipelineVersion, status, updatedAt)
                VALUES (1, 'ru', 'GEMINI', NULL, 'p', 's', 'v', 'COMPLETE', 1)
                """.trimIndent(),
            )
            connection.exec("PRAGMA user_version = 6")
        }
        return file
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-restore-test").toFile().also { tempDirs += it }
}

private fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

private fun SQLiteConnection.int(sql: String): Int =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for: $sql" }
        statement.getLong(0).toInt()
    }

private const val LibrarySchema = LibrarySchemaVersion

private fun BackupRestoreService.restoreNow(input: java.io.InputStream): RestoreApplyResult =
    runBlocking { restore(input) }

private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}
