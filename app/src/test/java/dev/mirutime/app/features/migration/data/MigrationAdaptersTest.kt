package dev.mirutime.app.features.migration.data

import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.app.features.downloads.data.StoredText
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.NovelPrompt
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.core.text.RegexRule
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationAdaptersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `file adapter writes reads and deletes text separately from pages`() = runTest {
        val files = FakeDownloadFiles(temp.root)
        val adapter = DownloadFileStoreMigrationFiles(files)

        val stored = adapter.writeText(7, 11, "<p>hello</p>")
        assertEquals("<p>hello</p>", adapter.readText(stored.path))
        assertNotNull(adapter.readText(stored.path))
        assertTrue(File(stored.path).isFile)
        assertEquals(stored.bytes, File(stored.path).length())
        assertEquals(stored.sha256, "sha-<p>hello</p>")

        val page = adapter.writePage(7, 11, 0, "https://cdn.test/0.png", byteArrayOf(1, 2, 3))
        assertEquals(listOf(page), adapter.pagePaths(adapter.encodePagePaths(listOf(page))))
        assertEquals(byteArrayOf(1, 2, 3).toList(), adapter.readBytes(page)!!.toList())

        adapter.deleteText(7, 11)
        assertFalse(File(stored.path).isFile)
        assertTrue(File(page).isFile)

        adapter.deletePages(7, 11)
        assertFalse(File(page).isFile)
    }

    @Test
    fun `file adapter delegates book cleanup`() = runTest {
        val files = FakeDownloadFiles(temp.root)
        val adapter = DownloadFileStoreMigrationFiles(files)
        adapter.writeText(3, 4, "body")

        adapter.deleteBookFiles(3)

        assertEquals(listOf(3L), files.deletedBooks)
    }

    @Test
    fun `settings adapter copies per-novel values and never overwrites target`() = runTest {
        val old = "https://old.test/settings"
        val target = "https://new.test/settings"
        clear(old, target)
        AppSettings.novelTranslation[old] = TranslationConfig(enabled = true, target = "ru")
        AppSettings.novelRegexRules[old] = listOf(rule("a"))
        AppSettings.novelPrompts += NovelPrompt(old, "Old", "prompt", append = true)

        var saves = 0
        val adapter = AppSettingsMigrationSettings(save = { saves++ })
        adapter.copyNovelSettings(old, target)

        assertEquals("ru", AppSettings.novelTranslation[target]?.target)
        assertEquals("a", AppSettings.novelRegexRules[target]?.single()?.pattern)
        assertEquals("prompt", AppSettings.novelPrompts.single { it.bookUrl == target }.prompt)
        assertEquals("old stays until delete", "ru", AppSettings.novelTranslation[old]?.target)
        assertEquals(1, saves)

        AppSettings.novelTranslation[target] = TranslationConfig(target = "de")
        adapter.copyNovelSettings(old, target)
        assertEquals("de", AppSettings.novelTranslation[target]?.target)

        adapter.deleteNovelSettings(old)
        assertNull(AppSettings.novelTranslation[old])
        assertNull(AppSettings.novelRegexRules[old])
        assertTrue(AppSettings.novelPrompts.none { it.bookUrl == old })
        assertNotNull(AppSettings.novelTranslation[target])
        clear(old, target)
    }

    private fun clear(vararg urls: String) {
        urls.forEach { url ->
            AppSettings.novelTranslation.remove(url)
            AppSettings.novelRegexRules.remove(url)
            AppSettings.novelPrompts.removeAll { it.bookUrl == url }
        }
    }

    private fun rule(pattern: String) =
        RegexRule(1L, pattern, "", "", true, false)

    private class FakeDownloadFiles(private val root: File) : DownloadFileStore {
        val deletedBooks = mutableListOf<Long>()
        private val json = Json

        override fun writeText(bookId: Long, chapterId: Long, html: String): StoredText {
            val file = File(root, "text/$bookId/$chapterId.html")
            file.parentFile?.mkdirs()
            file.writeText(html)
            return StoredText(file.absolutePath, file.length(), "sha-$html")
        }

        override fun readText(path: String): String? =
            File(path).takeIf { it.isFile }?.readText()

        override fun writePage(
            bookId: Long,
            chapterId: Long,
            index: Int,
            url: String,
            bytes: ByteArray,
        ): String {
            val file = File(root, "pages/$bookId/$chapterId/$index.png")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return file.absolutePath
        }

        override fun textExists(path: String): Boolean = File(path).isFile

        override fun pagesExist(pagePathsJson: String): Boolean =
            pagePaths(pagePathsJson).all { File(it).isFile }

        override fun pagePaths(pagePathsJson: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(pagePathsJson) }
                .getOrDefault(emptyList())

        override fun encodePagePaths(paths: List<String>): String = json.encodeToString(paths)

        override fun deleteChapterFiles(bookId: Long, chapterId: Long) {
            File(root, "text/$bookId/$chapterId.html").delete()
            File(root, "pages/$bookId/$chapterId").deleteRecursively()
        }

        override fun deleteBookFiles(bookId: Long) {
            deletedBooks += bookId
        }
    }
}
