package dev.mirutime.core.log

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogExporterTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("miru-export-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes archives oldest first then the active log`() {
        val old = File(dir, "mirutime-${LocalDate.now().minusDays(2)}.log")
        val recent = File(dir, "mirutime-${LocalDate.now().minusDays(1)}.log")
        old.writeText("oldest\n")
        recent.writeText("recent\n")
        File(dir, MiruFileLogger.LOG_NAME).writeText("active\n")
        val out = ByteArrayOutputStream()

        val written = LogExporter.write(dir, out)

        assertEquals("oldest\n\nrecent\n\nactive\n", out.toString(Charsets.UTF_8))
        assertEquals(
            "oldest\n\nrecent\n\nactive\n".toByteArray(Charsets.UTF_8).size.toLong(),
            written,
        )
    }

    @Test
    fun `no logs writes nothing`() {
        val out = ByteArrayOutputStream()

        assertEquals(0L, LogExporter.write(dir, out))
        assertTrue(out.size() == 0)
    }

    @Test
    fun `empty files are skipped`() {
        File(dir, MiruFileLogger.LOG_NAME).createNewFile()
        val out = ByteArrayOutputStream()

        assertEquals(0L, LogExporter.write(dir, out))
        assertTrue(out.size() == 0)
    }
}
