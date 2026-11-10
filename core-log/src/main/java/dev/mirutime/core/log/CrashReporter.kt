package dev.mirutime.core.log

import android.os.Process

/**
 * Writes an uncaught exception to the file log, then hands the process back to
 * the handler that was installed before us. Formatting is a pure helper so it
 * can be tested without killing the JVM.
 */
object CrashReporter {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { MiruFileLogger.write("FATAL", "crash", summary(thread), error) }
            runCatching { MiruFileLogger.flush() }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // No framework handler (JVM tests, odd OEM boot paths): keep the
                // usual terminal behaviour instead of leaving a broken process.
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun format(thread: Thread, error: Throwable, timeMs: Long = System.currentTimeMillis()): String =
        MiruFileLogger.formatLine(
            level = "FATAL",
            tag = "crash",
            message = summary(thread),
            throwable = error,
            timeMs = timeMs,
            threadName = thread.name,
        )

    private fun summary(thread: Thread): String = "uncaught in thread ${thread.name}"
}
