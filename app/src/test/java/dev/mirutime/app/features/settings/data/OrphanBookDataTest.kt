package dev.mirutime.app.features.settings.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanBookDataTest {
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `inspect and sweep remove only folders without a live book`() {
        val root = Files.createTempDirectory("mirutime-orphans").toFile().also { roots += it }
        val covers = File(root, "covers")
        val text = File(root, "downloads/text")
        val manga = File(root, "downloads/manga")
        val validCover = file(covers, "1/cover", 10)
        val orphanCover = file(covers, "2/cover", 20)
        val validText = file(text, "1/1.html", 30)
        val orphanText = file(text, "3/1.html", 40)
        val validManga = file(manga, "2/1/1.jpg", 50)
        val orphanManga = file(manga, "4/1/1.jpg", 60)
        val unknown = file(covers, "tmp/cover", 70)

        val before = inspectOrphanBookData(
            filesDir = root,
            validBookIds = setOf(1L, 2L),
            validCoverIds = setOf(1L),
            now = Long.MAX_VALUE,
        )

        assertEquals(3, before.dirCount)
        assertEquals(120, before.bytes)
        assertTrue(orphanCover.exists())
        assertTrue(orphanText.exists())
        assertTrue(orphanManga.exists())

        val swept = sweepOrphanBookData(
            filesDir = root,
            validBookIds = setOf(1L, 2L),
            validCoverIds = setOf(1L),
            now = Long.MAX_VALUE,
        )

        assertEquals(3, swept.dirCount)
        assertEquals(120, swept.bytes)
        assertFalse(orphanCover.exists())
        assertFalse(orphanText.exists())
        assertFalse(orphanManga.exists())
        assertTrue(validCover.exists())
        assertTrue(validText.exists())
        assertTrue(validManga.exists())
        assertTrue(unknown.exists())
        assertEquals(
            0,
            inspectOrphanBookData(root, setOf(1L, 2L), setOf(1L), now = Long.MAX_VALUE).dirCount,
        )
    }

    @Test
    fun `sweep removes local book entries without a live copy`() {
        val root = Files.createTempDirectory("mirutime-local-orphans").toFile().also { roots += it }
        val novel = File(root, "local_books/local-books")
        val manga = File(root, "local_books/local-manga")
        val validCopy = file(novel, "sha-one.epub", 10)
        val validBodies = file(novel, "sha-one/1.html", 20)
        val orphanCopy = file(novel, "orphan.epub", 30)
        val orphanBodies = file(novel, "orphan/1.html", 40)
        val staging = file(novel, ".import-stale/1.html", 50)
        val validFolder = file(manga, "folder/chapters/1/1.jpg", 60)
        val validBodyOnly = file(novel, "body-only/chapters/1.html", 65)
        val orphanFolder = file(manga, "old-folder/1.jpg", 70)

        val names = localValidNames(
            listOf(
                "local_books/local-books/sha-one.epub",
                "local_books/local-manga/folder",
                "local_books/local-books/body-only/chapters/1.html",
            ),
        )
        val swept = sweepOrphanBookData(
            filesDir = root,
            validBookIds = emptySet(),
            validCoverIds = emptySet(),
            validLocalNames = names,
            now = Long.MAX_VALUE,
        )

        assertEquals(3, swept.dirCount)
        assertEquals(140, swept.bytes)
        assertTrue(validCopy.exists())
        assertTrue(validBodies.exists())
        assertTrue(validFolder.exists())
        assertTrue(validBodyOnly.exists())
        assertTrue(staging.exists())
        assertFalse(orphanCopy.exists())
        assertFalse(orphanBodies.exists())
        assertFalse(orphanFolder.exists())
    }

    private fun file(root: File, relative: String, bytes: Int): File =
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }
}
