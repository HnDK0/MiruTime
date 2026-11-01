package dev.mirutime.core.log

import java.io.File
import java.io.OutputStream

/** Streams the current log and its single rotated predecessor to a SAF output. */
object LogExporter {
    fun files(dir: File): List<File> =
        listOf(
            File(dir, MiruFileLogger.LOG_NAME),
            File(dir, MiruFileLogger.ROTATED_NAME),
        ).filter { it.isFile && it.length() > 0 }

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
