package dev.mirutime.app.features.library.data

import dev.mirutime.core.host.FileSystem
import dev.mirutime.data.library.LibraryBookFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileCleanupTest {

    private class TreeFileSystem(initial: Set<String>) : FileSystem {
        val files = initial.toMutableSet()
        val deleted = mutableListOf<String>()
        val threads = mutableListOf<String>()

        override fun read(path: String): ByteArray? = null
        override fun write(path: String, data: ByteArray) = Unit
        override fun exists(path: String): Boolean =
            path in files || files.any { it.startsWith("${path.trimEnd('/')}/") }

        override fun delete(path: String) {
            deleted += path
            threads += Thread.currentThread().name
            files.remove(path)
            files.removeAll { it.startsWith("${path.trimEnd('/')}/") }
        }

        override fun rename(from: String, to: String) = Unit

        override fun list(dir: String): List<String> {
            val prefix = dir.trimEnd('/') + "/"
            return files
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { "$prefix$it" }
        }
    }

    @Test
    fun `single file import removes copy body page cover and keeps the source`() = runTest {
        val source = "saf_source/Book.epub"
        val fileSystem = TreeFileSystem(
            setOf(
                source,
                "local_books/local-books/sha.epub",
                "local_books/local-books/sha/1.html",
                "local_books/local-books/sha/pages/1.jpg",
                "covers/7/cover",
            ),
        )
        val callerThread = Thread.currentThread().name
        val files = LibraryBookFiles(
            bookId = 7,
            coverPath = "covers/7/cover",
            copyPath = "local_books/local-books/sha.epub",
            folderCopy = false,
            bodyPaths = listOf("local_books/local-books/sha/1.html"),
            pagePathsJson = listOf("[\"local_books/local-books/sha/pages/1.jpg\"]"),
        )

        removeLibraryFiles(fileSystem, listOf(files))

        assertTrue(fileSystem.deleted.containsAll(listOf(
            "local_books/local-books/sha.epub",
            "local_books/local-books/sha/1.html",
            "local_books/local-books/sha/pages/1.jpg",
            "covers/7/cover",
        )))
        assertTrue(fileSystem.files.none { it.startsWith("local_books/local-books/sha") })
        assertFalse(fileSystem.deleted.contains(source))
        assertTrue(fileSystem.exists(source))
        assertFalse(fileSystem.threads.any { it == callerThread })
    }

    @Test
    fun `folder import deletes nested page tree and cover`() = runTest {
        val source = "saf_source/Manga"
        val root = "local_books/local-manga/sha"
        val fileSystem = TreeFileSystem(
            setOf(
                source,
                "$root/Chapter 1/1.jpg",
                "$root/Chapter 1/2.jpg",
                "$root/Chapter 2/1.jpg",
                "covers/8/cover",
            ),
        )
        removeLibraryFiles(
            fileSystem,
            listOf(
                LibraryBookFiles(
                    bookId = 8,
                    coverPath = "covers/8/cover",
                    copyPath = root,
                    folderCopy = true,
                    bodyPaths = emptyList(),
                    pagePathsJson = listOf("[\"$root/Chapter 1/1.jpg\"]"),
                ),
            ),
        )

        assertTrue(fileSystem.deleted.containsAll(listOf(
            "$root/Chapter 1/1.jpg",
            "$root/Chapter 1/2.jpg",
            "$root/Chapter 2/1.jpg",
            "covers/8/cover",
        )))
        assertTrue(fileSystem.files.none { it.startsWith("$root/") })
        assertFalse(fileSystem.deleted.contains(source))
        assertTrue(fileSystem.exists(source))
    }
}
