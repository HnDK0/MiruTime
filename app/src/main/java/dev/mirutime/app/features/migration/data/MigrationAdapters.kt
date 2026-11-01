package dev.mirutime.app.features.migration.data

import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.data.library.migration.BookMigrationFileStore
import dev.mirutime.data.library.migration.BookMigrationSettingsStore
import dev.mirutime.data.library.migration.MigrationStoredText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the migration core to the app-private download store. The core is
 * suspend; DownloadFileStore is synchronous, so every call hops to IO.
 *
 * Written paths are tracked per instance because the core deletes text and
 * pages separately during rollback. Deriving paths from ids would duplicate
 * the download store's layout and could delete bytes this migration never
 * wrote.
 */
class DownloadFileStoreMigrationFiles(
    private val files: DownloadFileStore,
) : BookMigrationFileStore {
    private val textPaths = ConcurrentHashMap<String, String>()
    private val pagePaths = ConcurrentHashMap<String, List<String>>()

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        files.readText(path)
    }

    override suspend fun writeText(
        bookId: Long,
        chapterId: Long,
        text: String,
    ): MigrationStoredText = withContext(Dispatchers.IO) {
        val stored = files.writeText(bookId, chapterId, text)
        textPaths[key(bookId, chapterId)] = stored.path
        MigrationStoredText(path = stored.path, bytes = stored.bytes, sha256 = stored.sha256)
    }

    override suspend fun readBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun writePage(
        bookId: Long,
        chapterId: Long,
        index: Int,
        sourcePath: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val path = files.writePage(bookId, chapterId, index, sourcePath, bytes)
        pagePaths.compute(key(bookId, chapterId)) { _, existing ->
            existing.orEmpty() + path
        }
        path
    }

    override suspend fun pagePaths(pagePathsJson: String): List<String> = withContext(Dispatchers.IO) {
        files.pagePaths(pagePathsJson)
    }

    override suspend fun encodePagePaths(paths: List<String>): String = withContext(Dispatchers.IO) {
        files.encodePagePaths(paths)
    }

    override suspend fun deleteText(bookId: Long, chapterId: Long) {
        val path = textPaths.remove(key(bookId, chapterId)) ?: return
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    override suspend fun deletePages(bookId: Long, chapterId: Long) {
        val paths = pagePaths.remove(key(bookId, chapterId)) ?: return
        withContext(Dispatchers.IO) { paths.forEach { File(it).delete() } }
    }

    override suspend fun deleteBookFiles(bookId: Long) = withContext(Dispatchers.IO) {
        files.deleteBookFiles(bookId)
    }

    private fun key(bookId: Long, chapterId: Long): String = "$bookId:$chapterId"
}

/**
 * Moves URL-keyed per-novel settings. Target values are never overwritten:
 * if the user already configured the target book, that config wins.
 */
class AppSettingsMigrationSettings(
    private val save: suspend () -> Unit = {},
) : BookMigrationSettingsStore {
    override suspend fun copyNovelSettings(oldBookUrl: String, newBookUrl: String) {
        AppSettings.novelTranslation[oldBookUrl]?.let { config ->
            if (newBookUrl !in AppSettings.novelTranslation) {
                AppSettings.novelTranslation[newBookUrl] = config
            }
        }
        AppSettings.novelRegexRules[oldBookUrl]?.let { rules ->
            if (newBookUrl !in AppSettings.novelRegexRules) {
                AppSettings.novelRegexRules[newBookUrl] = rules
            }
        }
        AppSettings.novelPrompts.firstOrNull { it.bookUrl == oldBookUrl }?.let { prompt ->
            if (AppSettings.novelPrompts.none { it.bookUrl == newBookUrl }) {
                AppSettings.novelPrompts += prompt.copy(bookUrl = newBookUrl)
            }
        }
        saveDurably()
    }

    override suspend fun deleteNovelSettings(bookUrl: String) {
        AppSettings.novelTranslation.remove(bookUrl)
        AppSettings.novelRegexRules.remove(bookUrl)
        AppSettings.novelPrompts.removeAll { it.bookUrl == bookUrl }
        saveDurably()
    }

    private suspend fun saveDurably() {
        withContext(Dispatchers.IO) { save() }
    }
}
