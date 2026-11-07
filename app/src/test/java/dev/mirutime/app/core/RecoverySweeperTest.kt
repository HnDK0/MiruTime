package dev.mirutime.app.core

import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.ImportJobEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoverySweeperTest {
    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mirutime-sweeper-files").toFile()
        cacheDir = Files.createTempDirectory("mirutime-sweeper-cache").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `marks interrupted import and migration rows failed`() = runTest {
        val jobs = mutableListOf(
            ImportJobEntity(id = 1, kind = "local-books", status = "RUNNING", createdAt = 1),
            ImportJobEntity(
                id = 2,
                kind = "local-books",
                status = "COMPLETE",
                createdAt = 2,
                finishedAt = 3,
            ),
        )
        val migrations = mutableListOf(
            migration(id = 1, status = "running"),
            migration(id = 2, status = "completed"),
        )
        val savedJobs = mutableListOf<ImportJobEntity>()
        val savedMigrations = mutableListOf<BookMigrationEntity>()

        val report = sweeper(jobs, savedJobs, migrations, savedMigrations).sweep()

        assertEquals(1, report.importJobsFailed)
        assertEquals(1, report.migrationsFailed)
        assertEquals("FAILED", savedJobs.single().status)
        assertEquals(NOW, savedJobs.single().finishedAt)
        assertTrue(savedJobs.single().error!!.contains("interrupted"))
        assertEquals("failed", savedMigrations.single().status)
        assertEquals(NOW, savedMigrations.single().finishedAt)
    }

    @Test
    fun `deletes only import staging directories`() = runTest {
        val mangaRoot = File(filesDir, "local_books/local-manga")
        val staging = File(mangaRoot, ".import-abc").apply { mkdirs() }
        File(staging, "page.jpg").writeText("page")
        check(staging.setLastModified(NOW - 1)) { "cannot set staging mtime" }
        val kept = File(mangaRoot, "keep").apply { mkdirs() }
        val booksStaging = File(filesDir, "local_books/local-books/.import-x").apply {
            mkdirs()
            setLastModified(NOW - 1)
        }
        val freshStaging = File(mangaRoot, ".import-fresh").apply {
            mkdirs()
            setLastModified(NOW)
        }

        val report = sweeper(
            emptyList(),
            mutableListOf(),
            emptyList(),
            mutableListOf(),
        ).sweep()

        assertEquals(2, report.stagingDirsDeleted)
        assertFalse(staging.exists())
        assertFalse(booksStaging.exists())
        assertTrue(kept.exists())
        assertTrue(freshStaging.exists())
    }

    @Test
    fun `deletes only stale sweepable cache files`() = runTest {
        val old = NOW - STALE_MS - 1
        val oldEpub = cacheFile("miru-epub-old.epub", old)
        val freshEpub = cacheFile("miru-epub-new.epub", NOW)
        val oldImport = cacheFile("local-import-old.tmp", old)
        val oldBackup = cacheFile("mirutime-backup-old.tmp", old)
        val other = cacheFile("notes.txt", old)

        val report = sweeper(
            emptyList(),
            mutableListOf(),
            emptyList(),
            mutableListOf(),
        ).sweep()

        assertEquals(3, report.tempFilesDeleted)
        assertFalse(oldEpub.exists())
        assertFalse(oldImport.exists())
        assertFalse(oldBackup.exists())
        assertTrue(freshEpub.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `second sweep is a no-op`() = runTest {
        val jobs = mutableListOf(
            ImportJobEntity(id = 1, kind = "local-manga", status = "RUNNING", createdAt = 1),
        )
        val migrations = mutableListOf(migration(id = 1, status = "running"))
        val sweeper = sweeper(jobs, mutableListOf(), migrations, mutableListOf())

        assertEquals(1, sweeper.sweep().importJobsFailed)
        jobs[0] = jobs[0].copy(status = "FAILED")
        migrations[0] = migrations[0].copy(status = "failed")

        val second = sweeper.sweep()
        assertEquals(0, second.importJobsFailed)
        assertEquals(0, second.migrationsFailed)
        assertEquals(0, second.stagingDirsDeleted)
        assertEquals(0, second.tempFilesDeleted)
    }

    @Test
    fun `a failing step does not block housekeeping`() = runTest {
        val oldEpub = cacheFile("miru-epub-stale.epub", NOW - STALE_MS - 1)
        val sweeper = RecoverySweeper(
            filesDir = filesDir,
            cacheDir = cacheDir,
            loadImportJobs = { error("database is gone") },
            saveImportJob = { 0 },
            loadMigrations = { emptyList() },
            saveMigration = { 0 },
            now = { NOW },
            staleTempMs = STALE_MS,
        )

        val report = sweeper.sweep()

        assertEquals(0, report.importJobsFailed)
        assertEquals(1, report.tempFilesDeleted)
        assertFalse(oldEpub.exists())
    }

    @Test
    fun `does not touch work started after the sweep began`() = runTest {
        val jobs = mutableListOf(
            ImportJobEntity(id = 1, kind = "local-books", status = "RUNNING", createdAt = NOW - 1),
            ImportJobEntity(id = 2, kind = "local-books", status = "RUNNING", createdAt = NOW),
        )
        val migrations = mutableListOf(
            migration(id = 1, status = "running").copy(createdAt = NOW - 1),
            migration(id = 2, status = "running").copy(createdAt = NOW),
        )
        val savedJobs = mutableListOf<ImportJobEntity>()
        val savedMigrations = mutableListOf<BookMigrationEntity>()

        val report = sweeper(jobs, savedJobs, migrations, savedMigrations).sweep()

        assertEquals(1, report.importJobsFailed)
        assertEquals(1, report.migrationsFailed)
        assertEquals(1L, savedJobs.single().id)
        assertEquals(1L, savedMigrations.single().id)
    }

    private fun sweeper(
        jobs: List<ImportJobEntity>,
        savedJobs: MutableList<ImportJobEntity>,
        migrations: List<BookMigrationEntity>,
        savedMigrations: MutableList<BookMigrationEntity>,
    ) = RecoverySweeper(
        filesDir = filesDir,
        cacheDir = cacheDir,
        loadImportJobs = { jobs },
        saveImportJob = { job ->
            savedJobs += job
            job.id
        },
        loadMigrations = { migrations },
        saveMigration = { migration ->
            savedMigrations += migration
            migration.id
        },
        now = { NOW },
        staleTempMs = STALE_MS,
    )

    private fun cacheFile(name: String, modifiedAt: Long): File =
        File(cacheDir, name).apply {
            writeText("temp")
            check(setLastModified(modifiedAt)) { "cannot set mtime for $name" }
        }

    private companion object {
        const val NOW = 10_000_000L
        const val STALE_MS = 1_000L
    }
}

private fun migration(id: Long, status: String) = BookMigrationEntity(
    id = id,
    oldBookId = null,
    newBookId = null,
    oldInstallId = null,
    newInstallId = null,
    batchId = null,
    status = status,
    createdAt = 1L,
)
