package dev.mirutime.core.log

import java.io.File
import java.io.OutputStream

/** Streams the daily archives oldest-first, then the active log. */
object LogExporter {
    fun files(dir: File): List<File> =
        MiruFileLogger.archives(dir) +
            listOf(File(dir, MiruFileLogger.LOG_NAME))
                .filter { it.isFile && it.length() > 0 }

    /** Returns bytes written; 0 means there was nothing to export. */
    fun write(dir: File, output: OutputStream): Long {
        val sources = files(dir)
        var total = 0L
        sources.forEachIndexed { index, file ->
            if (index > 0) {
                output.write('\n'.code)
                total += 1
            }
            file.inputStream().use { input -> total += input.copyTo(output) }
        }
        output.flush()
        return total
    }
}
