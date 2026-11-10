package dev.mirutime.core.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiruLogTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("miru-event-test").toFile()
        MiruFileLogger.install(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `event writes stable key value line to file`() {
        MiruLog.event("library", "load", "books" to 12, "shelves" to 3)
        MiruFileLogger.flush()

        val line = MiruFileLogger.logFile(dir).readLines().single()
        assertTrue(line.contains(" INFO MiruTime.library ["))
        assertTrue(line.endsWith("event=load books=12 shelves=3"))
    }

    @Test
    fun `event skips nulls and flattens newlines`() {
        val formatted = MiruLog.formatEvent(
            "open",
            arrayOf("chapter" to 2, "note" to null, "value" to "a\nb c=d"),
        )

        assertEquals("event=open chapter=2 value=a_b_c_d", formatted)
    }

    @Test
    fun `event still passes through file redaction`() {
        MiruLog.event("cf", "solve", "token" to "super-secret-value", "cookies" to true)
        MiruFileLogger.flush()

        val line = MiruFileLogger.logFile(dir).readText()
        assertFalse(line.contains("super-secret-value"))
        assertTrue(line.contains("[REDACTED]"))
        assertTrue(line.contains("cookies=true"))
    }
}
