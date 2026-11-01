package dev.mirutime.core.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test
    fun `formats fatal entry with thread and stack`() {
        val error = IllegalStateException("boom")
        val text = CrashReporter.format(Thread("worker-1"), error, timeMs = 0L)

        assertTrue(text.startsWith("1970-01-01T00:00:00Z FATAL crash [worker-1]"))
        assertTrue(text.contains("uncaught in thread worker-1"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("CrashReporterTest"))
    }

    @Test
    fun `redacts secrets from the exception message`() {
        val text = CrashReporter.format(
            Thread("worker-1"),
            IllegalStateException("GET https://x/y?token=leaked123 failed"),
            timeMs = 0L,
        )

        assertFalse(text.contains("leaked123"))
        assertTrue(text.contains("[REDACTED]"))
    }
}
