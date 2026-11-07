package dev.mirutime.data.library

/**
 * Row transitions shared by the startup RecoverySweeper and its DAO test.
 * A row older than [startedAt] was already RUNNING when this process started;
 * rows created after launch can still be live and must not be touched.
 */
suspend fun failInterruptedImports(
    load: suspend () -> List<ImportJobEntity>,
    save: suspend (ImportJobEntity) -> Long,
    startedAt: Long,
    now: Long,
): Int {
    val interrupted = load().filter {
        it.status.equals("RUNNING", ignoreCase = true) && it.createdAt < startedAt
    }
    interrupted.forEach { job ->
        save(job.copy(status = "FAILED", error = "interrupted by process death", finishedAt = now))
    }
    return interrupted.size
}

suspend fun failInterruptedMigrations(
    load: suspend () -> List<BookMigrationEntity>,
    save: suspend (BookMigrationEntity) -> Long,
    startedAt: Long,
    now: Long,
): Int {
    val interrupted = load().filter {
        it.status.equals("running", ignoreCase = true) && it.createdAt < startedAt
    }
    interrupted.forEach { migration ->
        save(
            migration.copy(
                status = "failed",
                error = "interrupted by process death",
                finishedAt = now,
            ),
        )
    }
    return interrupted.size
}
