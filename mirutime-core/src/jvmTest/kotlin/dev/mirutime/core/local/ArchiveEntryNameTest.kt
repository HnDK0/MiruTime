package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveEntryNameTest {
    @Test
    fun namesWithColonsAndDotsAreSafe() {
        val names = listOf(
            "images/cover.jpg",
            "Chapter 5: The Return/001.jpg",
            "A: Origins/01.png",
            "Wait... what/01.png",
            "page001..jpg",
        )
        for (name in names) {
            assertTrue(name.isSafeBookPath(), name)
        }
    }

    @Test
    fun traversalAndAbsolutePrefixesAreStillRejected() {
        val names = listOf("../cover.jpg", "a/../../etc/passwd", "/abs/x.jpg", "C:/x.jpg", "C:\\x.jpg", "a\\..\\b", "")
        for (name in names) {
            assertFalse(name.isSafeBookPath(), name)
        }
    }

    @Test
    fun comicPagesInFoldersWithColonsAreListed() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (name in listOf("Chapter 5: The Return/001.jpg", "Wait... what/002.jpg", "../evil.jpg")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val file = File.createTempFile("mirutime-test", ".cbz")
        try {
            file.writeBytes(bytes.toByteArray())
            val names = CbzFileReader(file).use { it.imageNames }
            assertEquals(listOf("Chapter 5: The Return/001.jpg", "Wait... what/002.jpg"), names)
        } finally {
            file.delete()
        }
    }
}
