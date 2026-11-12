package dev.mirutime.core.opds

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpdsFileNormalizerTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "opds-normalizer-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epub(): ByteArray = zip(
        listOf(
            "META-INF/container.xml" to
                "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>"
                    .toByteArray(),
            "OEBPS/content.opf" to "<package/>".toByteArray(),
        ),
    )

    private fun file(name: String, bytes: ByteArray): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun `epub wrapped in zip is unwrapped once`() {
        val wrapped = file("book.epub.zip", zip(listOf("book.epub" to epub())))
        assertTrue(OpdsFileNormalizer.unwrapEpubZip(wrapped))
        ZipFile(wrapped).use { assertTrue(it.getEntry("META-INF/container.xml") != null) }
    }

    @Test
    fun `real epub and non archives are left alone`() {
        val plain = file("book.epub", epub())
        assertFalse(OpdsFileNormalizer.unwrapEpubZip(plain))
        ZipFile(plain).use { assertTrue(it.getEntry("META-INF/container.xml") != null) }

        val junk = file("broken.epub", "not a zip".toByteArray())
        assertFalse(OpdsFileNormalizer.unwrapEpubZip(junk))
        assertTrue(junk.readText() == "not a zip")
    }
}
