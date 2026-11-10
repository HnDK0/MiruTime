package dev.mirutime.core.log

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiruFileLoggerTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("miru-log-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `appends utf8 lines with level tag and thread`() {
        MiruFileLogger.install(dir)
        MiruFileLogger.write("INFO", "MiruTime.app", "started глава")
        MiruFileLogger.write("ERROR", "MiruTime.reader", "broken")
        MiruFileLogger.flush()

        val lines = MiruFileLogger.logFile(dir).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains(" INFO MiruTime.app ["))
        assertTrue(lines[0].endsWith("started глава"))
        assertTrue(lines[1].contains(" ERROR MiruTime.reader ["))
        assertTrue(lines[1].endsWith("broken"))
    }

    @Test
    fun `rotates once when the cap is reached`() {
        MiruFileLogger.install(dir, maxBytes = 120)
        val first = "a".repeat(100)
        val second = "b".repeat(100)

        MiruFileLogger.write("INFO", "t", first)
        MiruFileLogger.write("INFO", "t", second)
        MiruFileLogger.flush()

        val current = MiruFileLogger.logFile(dir).readText().trimEnd()
        val rotated = MiruFileLogger.rotatedFile(dir).readText().trimEnd()
        assertTrue(current.endsWith(second))
        assertFalse(current.contains(first))
        assertTrue(rotated.endsWith(first))
    }

    @Test
    fun `redacts headers query secrets and key values`() {
        val raw = listOf(
            "Authorization: Bearer abcdefghijklmnop",
            "x-api-key: super-secret-key",
            "https://example.com/x?token=abc123&id=7",
            "password=hunter2",
        ).joinToString(" | ")

        val redacted = MiruFileLogger.redact(raw)

        for (secret in listOf("abcdefghijklmnop", "super-secret-key", "abc123", "hunter2")) {
            assertFalse("leaked $secret", redacted.contains(secret))
        }
        assertTrue(redacted.contains("id=7"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `leaves non-secret fields readable`() {
        val raw = "chapter=12 source=example.org status=200 url=https://example.org/book"

        assertEquals(raw, MiruFileLogger.redact(raw))
    }

    @Test
    fun `caps very long messages`() {
        MiruFileLogger.install(dir)
        MiruFileLogger.write("INFO", "t", "x".repeat(10_000))
        MiruFileLogger.flush()

        val text = MiruFileLogger.logFile(dir).readText()
        assertTrue(text.contains("[truncated]"))
        assertTrue(text.length < 2_500)
    }
}
