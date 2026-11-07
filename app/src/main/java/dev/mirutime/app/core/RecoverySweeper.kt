package dev.mirutime.app.core

import dev.mirutime.core.log.MiruLog
import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.ImportJobEntity
import dev.mirutime.data.library.failInterruptedImports as failRecoveryImports
import dev.mirutime.data.library.failInterruptedMigrations as failRecoveryMigrations
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Startup housekeeping for state that a process death can leave behind.
 *
 * It deliberately never deletes user data:
 * - interrupted import jobs are marked FAILED, only their `.import-*` staging
 *   directories are removed;
 * - interrupted migrations are marked FAILED, the target book is kept;
 * - only cache temp files older than [staleTempMs] are removed.
 *
 * Everything is idempotent: a second run finds nothing to do.
 */
class RecoverySweeper(
    private val filesDir: File,
    private val cacheDir: File,
    private val loadImportJobs: suspend () -> List<ImportJobEntity>,
    private val saveImportJob: suspend (ImportJobEntity) -> Long,
    private val loadMigrations: suspend () -> List<BookMigrationEntity>,
    private val saveMigration: suspend (BookMigrationEntity) -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val staleTempMs: Long = DEFAULT_STALE_TEMP_MS,
) {
    data class Report(
        val importJobsFailed: Int,
        val migrationsFailed: Int,
        val stagingDirsDeleted: Int,
        val tempFilesDeleted: Int,
    )

    suspend fun sweep(): Report {
        val startedAt = now()
        val report = Report(
            importJobsFailed = step("import-jobs sweep failed", 0) {
                failInterruptedImports(startedAt)
            },
            migrationsFailed = step("migrations sweep failed", 0) {
                failInterruptedMigrations(startedAt)
            },
            stagingDirsDeleted = step("import staging sweep failed", 0) {
                deleteImportStaging(startedAt)
            },
            tempFilesDeleted = step("temp sweep failed", 0) { deleteStaleTemps() },
        )
        if (report != Report(0, 0, 0, 0)) {
            MiruLog.event(
                "recovery",
                "sweep",
                "imports" to report.importJobsFailed,
                "migrations" to report.migrationsFailed,
                "staging" to report.stagingDirsDeleted,
                "temps" to report.tempFilesDeleted,
            )
        }
        return report
    }

    private suspend fun <T> step(
        message: String,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MiruLog.e("recovery", message, e)
        fallback
    }

    private suspend fun failInterruptedImports(startedAt: Long): Int =
        failRecoveryImports(
            load = loadImportJobs,
            save = saveImportJob,
            startedAt = startedAt,
            now = now(),
        )

    private suspend fun failInterruptedMigrations(startedAt: Long): Int =
        failRecoveryMigrations(
            load = loadMigrations,
            save = saveMigration,
            startedAt = startedAt,
            now = now(),
        )

    private fun deleteImportStaging(startedAt: Long): Int {
        var deleted = 0
        for (installId in LOCAL_INSTALL_IDS) {
            val root = File(filesDir, "local_books/$installId")
            val staging = root.listFiles { file ->
                file.isDirectory &&
                    file.name.startsWith(IMPORT_STAGING_PREFIX) &&
                    file.lastModified() < startedAt
            }.orEmpty()
            staging.forEach { dir ->
                if (dir.deleteRecursively()) deleted++
            }
        }
        return deleted
    }

    private fun deleteStaleTemps(): Int {
        val cutoff = now() - staleTempMs
        val files = cacheDir.listFiles() ?: return 0
        return files.count { file ->
            file.isFile &&
                file.lastModified() < cutoff &&
                isSweepableTemp(file.name) &&
                file.delete()
        }
    }

    private fun isSweepableTemp(name: String): Boolean =
        (name.startsWith(EPUB_EXPORT_PREFIX) && name.endsWith(".epub")) ||
            (name.startsWith(IMPORT_TEMP_PREFIX) && name.endsWith(".tmp")) ||
            (name.startsWith(BACKUP_TEMP_PREFIX) && name.endsWith(".tmp"))

    private companion object {
        const val IMPORT_STAGING_PREFIX = ".import-"
        const val EPUB_EXPORT_PREFIX = "miru-epub-"
        const val IMPORT_TEMP_PREFIX = "local-import-"
        const val BACKUP_TEMP_PREFIX = "mirutime-backup-"
        val LOCAL_INSTALL_IDS = listOf("local-books", "local-manga")
        const val DEFAULT_STALE_TEMP_MS = 24L * 60 * 60 * 1000
    }
}
