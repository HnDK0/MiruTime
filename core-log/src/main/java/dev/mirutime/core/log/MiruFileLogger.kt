package dev.mirutime.core.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.Locale

/**
 * Plain-file log sink for support exports. The active file is
 * `mirutime.log`; when the day changes it is appended to
 * `mirutime-yyyy-MM-dd.log`, archives older than [RETENTION_DAYS] are
 * pruned, and the total size is capped at [MAX_BYTES]. The newest data is
 * never deleted: one noisy archive and the active file may exceed the cap.
 *
 * Logcat stays the live channel; this only keeps the recent history a user can
 * attach to a bug report. Writes are synchronized because downloads and the UI
 * log from different threads.
 */
object MiruFileLogger {
    const val LOG_DIR = "logs"
    const val LOG_NAME = "mirutime.log"
    const val RETENTION_DAYS = 14
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val LEGACY_ROTATED_NAME = "mirutime.log.1"
    private const val ARCHIVE_PREFIX = "mirutime-"
    private const val ARCHIVE_SUFFIX = ".log"
    private const val MAX_MESSAGE_CHARS = 2_000
    private const val TRUNCATED = "…[truncated]"
    private val ARCHIVE_NAME = Regex("""^mirutime-(\d{4}-\d{2}-\d{2})\.log$""")

    private val lock = Any()

    // File writes must never block the UI thread or a coroutine caller; the
    // single writer preserves order. ERROR/FATAL callers flush explicitly.
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "miru-log-write").apply { isDaemon = true }
    }

    @Volatile
    private var directory: File? = null

    @Volatile
    private var maxBytes: Long = MAX_BYTES

    fun install(context: Context) {
        install(File(context.filesDir, LOG_DIR))
    }

    fun install(dir: File) {
        install(dir, MAX_BYTES)
    }

    internal fun install(dir: File, maxBytes: Long) {
        synchronized(lock) {
            directory = dir
            this.maxBytes = maxBytes
            runCatching { dir.mkdirs() }
        }
        // Maintenance may copy a large archive; keep it off the startup thread.
        // It runs on the single writer before any log line queued after install.
        writer.execute {
            synchronized(lock) {
                runCatching {
                    migrateLegacy(dir)
                    rotateIfNeeded(dir)
                    prune(dir)
                }
            }
        }
    }

    /** Null until [install] runs; screens use it to show an empty export. */
    fun currentDirectory(): File? = directory

    fun logFile(): File? = directory?.let { File(it, LOG_NAME) }

    fun logFile(dir: File): File = File(dir, LOG_NAME)

    /** Newest daily archive, or null before the first rotation. */
    fun rotatedFile(): File? = directory?.let { rotatedFile(it) }

    fun rotatedFile(dir: File): File? = archives(dir).lastOrNull()

    /** Daily archives oldest first; the active file is not included. */
    internal fun archives(dir: File): List<File> =
        dir.listFiles { file ->
            file.isFile && ARCHIVE_NAME.matches(file.name) && file.length() > 0
        }
            ?.sortedBy { archiveDate(it) ?: LocalDate.MIN }
            .orEmpty()

    /** INFO/WARN/ERROR/FATAL. Never throws: a failed write must not recurse. */
    fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val dir = directory ?: return
        val line = formatLine(level, tag, message, throwable)
        writer.execute { append(dir, line) }
    }

    /** Blocks until queued lines are on disk (export and crash paths only). */
    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    fun formatLine(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        timeMs: Long = System.currentTimeMillis(),
        threadName: String = Thread.currentThread().name,
    ): String {
        val safeMessage = redact(cap(message))
        val builder = StringBuilder()
        builder.append(Instant.ofEpochMilli(timeMs).toString())
            .append(' ')
            .append(level.uppercase(Locale.US))
            .append(' ')
            .append(tag)
            .append(" [")
            .append(threadName)
            .append("] ")
            .append(safeMessage)
        if (throwable != null) {
            val stack = StringWriter().also { writer ->
                throwable.printStackTrace(PrintWriter(writer))
            }.toString()
            builder.append('\n').append(redact(cap(stack)))
        }
        return builder.toString()
    }

    /** Best-effort secret scrubbing; applied to messages and stack traces. */
    fun redact(text: String): String {
        var out = BEARER.replace(text) { match ->
            "${match.groupValues[1]} [REDACTED]"
        }
        out = KEY_VALUE.replace(out) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        out = QUERY_SECRET.replace(out) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        return out
    }

    private fun cap(text: String): String =
        if (text.length <= MAX_MESSAGE_CHARS) text else text.take(MAX_MESSAGE_CHARS) + TRUNCATED

    private fun append(dir: File, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        try {
            synchronized(lock) {
                if (!dir.isDirectory && !dir.mkdirs()) return
                val file = File(dir, LOG_NAME)
                rotateIfNeeded(dir)
                // A single day can be noisy; keep the active file bounded too.
                // shortcut: an archive can exceed the total cap, prune never
                // splits a day's file. Upgrade to a chunked archive if needed.
                if (file.length() > 0 && file.length() + bytes.size > maxBytes) {
                    archiveFile(dir, file, dayOf(file.lastModified()))
                    prune(dir)
                }
                FileOutputStream(file, true).use { it.write(bytes) }
            }
        } catch (_: Throwable) {
            // Drop the record instead of logging the failure (recursion guard).
        }
    }

    private fun rotateIfNeeded(dir: File) {
        val file = File(dir, LOG_NAME)
        if (!file.isFile || file.length() == 0L) return
        val day = dayOf(file.lastModified())
        if (day.isBefore(LocalDate.now())) {
            archiveFile(dir, file, day)
            prune(dir)
        }
    }

    /**
     * Appends [file] to its day archive. Appending instead of renaming keeps an
     * earlier archive from being overwritten after a restart or timezone shift.
     */
    private fun archiveFile(dir: File, file: File, day: LocalDate) {
        if (!file.isFile || file.length() == 0L) return
        val target = File(dir, "$ARCHIVE_PREFIX$day$ARCHIVE_SUFFIX")
        if (target.exists()) {
            FileOutputStream(target, true).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            file.delete()
        } else if (!file.renameTo(target)) {
            FileOutputStream(target).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            file.delete()
        }
    }

    private fun migrateLegacy(dir: File) {
        val legacy = File(dir, LEGACY_ROTATED_NAME)
        if (legacy.isFile && legacy.length() > 0L) {
            archiveFile(dir, legacy, dayOf(legacy.lastModified()))
        } else {
            legacy.delete()
        }
    }

    /** Deletes archives older than [RETENTION_DAYS], then oldest over the cap. */
    private fun prune(dir: File) {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS.toLong())
        archives(dir).forEach { file ->
            val day = archiveDate(file) ?: return@forEach
            if (day.isBefore(cutoff)) file.delete()
        }
        var remaining = archives(dir).toMutableList()
        var total = File(dir, LOG_NAME).length() + remaining.sumOf { it.length() }
        // Keep the newest archive even when it alone exceeds the cap: it is the
        // only copy of the most recent data.
        while (total > maxBytes && remaining.size > 1) {
            val oldest = remaining.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun archiveDate(file: File): LocalDate? =
        ARCHIVE_NAME.matchEntire(file.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun dayOf(timeMs: Long): LocalDate =
        Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()

    // shortcut: regex scrubber, not a parser. Covers the shapes we log
    // (headers, query params, key=value); extend when a real leak is found.
    private val BEARER = Regex(
        """(?i)\b(bearer)\s+[A-Za-z0-9._~+/=\-]+""",
    )
    private val KEY_VALUE = Regex(
        """(?i)\b(authorization|x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|client[_-]?secret)\b(\s*[:=]\s*)["']?([^"'\s,;&}]+)""",
    )
    private val QUERY_SECRET = Regex(
        """(?i)([?&](?:token|access_token|api_key|apikey|key|password|secret|sig|signature|auth)=)[^&\s#]+""",
    )
}
