package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/** Real-DAO guard for the transition functions the startup sweeper runs. */
class RecoveryRowsTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `interrupted import and migration rows become failed through Room`() = runTest {
        val db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val repository = LibraryRepository(db.libraryDao())
        try {
            repository.saveImportJob(
                ImportJobEntity(kind = "local-books", status = "RUNNING", createdAt = 1L),
            )
            repository.saveBookMigration(
                BookMigrationEntity(
                    oldBookId = 1L,
                    newBookId = 2L,
                    oldInstallId = "old",
                    newInstallId = "new",
                    batchId = "book:1:new:url",
                    status = "running",
                    createdAt = 1L,
                ),
            )

            assertEquals(
                1,
                failInterruptedImports(
                    load = repository::importJobs,
                    save = repository::saveImportJob,
                    startedAt = 2L,
                    now = 2L,
                ),
            )
            assertEquals(
                1,
                failInterruptedMigrations(
                    load = repository::bookMigrations,
                    save = repository::saveBookMigration,
                    startedAt = 2L,
                    now = 2L,
                ),
            )

            assertEquals("FAILED", repository.importJobs().single().status)
            assertEquals("failed", repository.bookMigrations().single().status)
        } finally {
            db.close()
        }
    }
}
