package dev.mirutime.app.features.settings.data

import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.RestoreBodyRow
import dev.mirutime.data.library.LocalMangaFileRow
import dev.mirutime.data.library.RestorePageRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPruneTest {

    @Test
    fun `prune resets downloaded flags for chapters whose pages it removed`() = runBlocking {
        val calls = mutableListOf<String>()
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "restorePageRows" -> listOf(
                    RestorePageRow(
                        chapterId = 7L,
                        pagePathsJson = """["local_books/local-manga/m/chapters/1/00001.jpg"]""",
                    ),
                )
                "restoreBodyRows" -> emptyList<RestoreBodyRow>()
                "localMangaFileRows" -> emptyList<LocalMangaFileRow>()
                "bookCovers" -> emptyList<BookCoverEntity>()
                "deleteChapterPages" -> {
                    calls += "delete-pages"
                    Unit
                }
                "resetOrphanedDownloadedFlags" -> {
                    calls += "reset-flags"
                    1
                }
                else -> error("unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        dao.pruneMissingRestoredContent { false }

        assertEquals(listOf("delete-pages", "reset-flags"), calls)
    }

    @Test
    fun `prune drops body rows whose files are gone and keeps the rest`() = runBlocking {
        val deleted = mutableListOf<Long>()
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "restorePageRows" -> emptyList<RestorePageRow>()
                "restoreBodyRows" -> listOf(
                    RestoreBodyRow(11L, "mirutime_plugins/chikari/bodies/1.html"),
                    RestoreBodyRow(12L, "mirutime_plugins/chikari/bodies/2.html"),
                )
                "localMangaFileRows" -> emptyList<LocalMangaFileRow>()
                "bookCovers" -> emptyList<BookCoverEntity>()
                "deleteChapterBody" -> {
                    deleted += args[0] as Long
                    Unit
                }
                "resetOrphanedDownloadedFlags" -> Unit
                else -> error("unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        dao.pruneMissingRestoredContent { it.endsWith("2.html") }

        assertEquals(listOf(11L), deleted)
    }
}
