package dev.mirutime.core.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.Executors
import java.util.Locale

/**
 * Plain-file log sink for support exports. No dependency, one file capped at
 * ~5 MB with one rotation to `mirutime.log.1`.
 *
 * Logcat stays the live channel; this only keeps the recent history a user can
 * attach to a bug report. Writes are synchronized because downloads and the UI
 * log from different threads.
 */
object MiruFileLogger {
    const val LOG_DIR = "logs"
    const val LOG_NAME = "mirutime.log"
    const val ROTATED_NAME = "mirutime.log.1"
    const val MAX_BYTES = 5L * 1024 * 1024
    private const val MAX_MESSAGE_CHARS = 2_000
    private const val TRUNCATED = "…[truncated]"

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
    }

    /** Null until [install] runs; screens use it to show an empty export. */
    fun currentDirectory(): File? = directory

    fun logFile(): File? = directory?.let { File(it, LOG_NAME) }

    fun logFile(dir: File): File = File(dir, LOG_NAME)

    fun rotatedFile(): File? = directory?.let { File(it, ROTATED_NAME) }

    fun rotatedFile(dir: File): File = File(dir, ROTATED_NAME)

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
                if (file.length() + bytes.size > maxBytes) rotate(dir, file)
                FileOutputStream(file, true).use { it.write(bytes) }
            }
        } catch (_: Throwable) {
            // Drop the record instead of logging the failure (recursion guard).
        }
    }

    private fun rotate(dir: File, file: File) {
        val rotated = File(dir, ROTATED_NAME)
        rotated.delete()
        if (!file.renameTo(rotated)) file.delete()
    }

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
