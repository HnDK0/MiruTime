package dev.mirutime.core.log

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
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
        install()
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
    fun `rotates the active file into a dated archive when the day changes`() {
        install()
        val current = MiruFileLogger.logFile(dir)
        current.writeText("old\n")
        val yesterday = LocalDate.now().minusDays(1)
        current.setLastModified(startOfDay(yesterday))

        MiruFileLogger.write("INFO", "t", "new")
        MiruFileLogger.flush()

        val archive = File(dir, "mirutime-$yesterday.log")
        assertTrue(archive.isFile)
        assertTrue(archive.readText().endsWith("old\n"))
        assertFalse(current.readText().contains("old"))
        assertTrue(current.readText().contains("new"))
    }

    @Test
    fun `a second rotation on the same day appends instead of overwriting`() {
        install()
        val yesterday = LocalDate.now().minusDays(1)
        val archive = File(dir, "mirutime-$yesterday.log")
        archive.writeText("older\n")
        val current = MiruFileLogger.logFile(dir)
        current.writeText("old\n")
        current.setLastModified(startOfDay(yesterday))

        MiruFileLogger.write("INFO", "t", "new")
        MiruFileLogger.flush()

        val text = archive.readText()
        assertTrue(text.contains("older"))
        assertTrue(text.contains("old"))
        assertFalse(text.contains("new"))
    }

    @Test
    fun `size cap archives the active file instead of overwriting it`() {
        install(120)
        val first = "a".repeat(100)
        val second = "b".repeat(100)

        MiruFileLogger.write("INFO", "t", first)
        MiruFileLogger.write("INFO", "t", second)
        MiruFileLogger.flush()

        val current = MiruFileLogger.logFile(dir).readText()
        val archive = MiruFileLogger.rotatedFile(dir)
        assertTrue(current.contains(second))
        assertFalse(current.contains(first))
        assertTrue(archive != null && archive.readText().contains(first))
    }

    @Test
    fun `prunes archives older than the retention window`() {
        install()
        val old = File(dir, "mirutime-${LocalDate.now().minusDays(15)}.log")
        val recent = File(dir, "mirutime-${LocalDate.now().minusDays(13)}.log")
        old.writeText("old\n")
        recent.writeText("recent\n")

        install()

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `size cap deletes the oldest archives and keeps the newest`() {
        install(10)
        val oldest = File(dir, "mirutime-${LocalDate.now().minusDays(2)}.log")
        val newest = File(dir, "mirutime-${LocalDate.now().minusDays(1)}.log")
        oldest.writeText("0123456789")
        newest.writeText("0123456789")

        install(10)

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `legacy rotated file is migrated into a dated archive`() {
        val legacy = File(dir, "mirutime.log.1")
        legacy.writeText("legacy\n")

        install()

        assertFalse(legacy.exists())
        val archive = MiruFileLogger.rotatedFile(dir)
        assertTrue(archive != null && archive.readText().contains("legacy"))
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
        install()
        MiruFileLogger.write("INFO", "t", "x".repeat(10_000))
        MiruFileLogger.flush()

        val text = MiruFileLogger.logFile(dir).readText()
        assertTrue(text.contains("[truncated]"))
        assertTrue(text.length < 2_500)
    }

    private fun install(maxBytes: Long = MiruFileLogger.MAX_BYTES) {
        MiruFileLogger.install(dir, maxBytes)
        MiruFileLogger.flush()
    }

    private fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
