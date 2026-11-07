package dev.mirutime.app.features.settings.data

import dev.mirutime.app.core.jsonStringArray
import dev.mirutime.data.library.ChapterPageFiles
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LibraryStorageUsage
import dev.mirutime.data.library.StoredFileBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Real device storage as shown by Settings -> Data. Only the five buckets the
// screen has rows for are exposed; `totalBytes` is exactly their sum.
data class StorageUsage(
    val databaseBytes: Long = 0,
    val imageBytes: Long = 0,
    // Only the disposable image_cache directory (Coil journals excluded):
    // `imageBytes` also contains user/imported covers that Clear never deletes.
    val imageCacheBytes: Long = 0,
    val novelCacheBytes: Long = 0,
    val mangaCacheBytes: Long = 0,
    val videoBytes: Long = 0,
    val clearableNovelCount: Int = 0,
    val clearableMangaCount: Int = 0,
    val clearableImageCount: Int = 0,
    val coverCount: Int = 0,
    val bookCount: Int = 0,
    val chapterCount: Int = 0,
    val localFileCount: Int = 0,
    val outOfLibraryBookCount: Int = 0,
    val orphanBookDirCount: Int = 0,
    val orphanBookBytes: Long = 0,
) {
    val totalBytes: Long
        get() = databaseBytes + imageBytes + novelCacheBytes + mangaCacheBytes + videoBytes

    fun bytesFor(bucket: StorageBucket): Long = when (bucket) {
        StorageBucket.Database -> databaseBytes
        StorageBucket.Images -> imageBytes
        StorageBucket.NovelCache -> novelCacheBytes
        StorageBucket.MangaCache -> mangaCacheBytes
        StorageBucket.OfflineVideo -> videoBytes
    }

    // False means the Data screen shows "not supported" instead of a Clear
    // button that would silently do nothing.
    fun isClearable(bucket: StorageBucket): Boolean = when (bucket) {
        StorageBucket.Database -> false
        // Only the remote-clearable rows and the Coil cache are removable;
        // user/imported covers keep the row non-clearable after Clear.
        StorageBucket.Images -> clearableImageCount > 0 || imageCacheBytes > 0
        StorageBucket.NovelCache -> clearableNovelCount > 0
        StorageBucket.MangaCache -> clearableMangaCount > 0
        StorageBucket.OfflineVideo -> videoBytes > 0
    }

    companion object {
        val Empty = StorageUsage()
    }
}

/**
 * Categories:
 * - Images: cover files plus source icons, with the DB sizes as fallback.
 * - Offline texts: chapter bodies plus imported novel copies.
 * - Offline manga: manga pages plus imported CBZ/folder copies.
 * Imported copies are library content and are never deleted by a Clear action.
 */
internal object StorageUsageAggregator {
    fun aggregate(
        library: LibraryStorageUsage,
        databaseBytes: Long,
        videoBytes: Long,
        imageCacheBytes: Long = 0,
        outOfLibraryBookCount: Int = 0,
        orphanBookDirCount: Int = 0,
        orphanBookBytes: Long = 0,
        sizeOf: (String) -> Long?,
    ): StorageUsage {
        val coverBytes = fileBytes(library.coverFiles, library.coverBytes, sizeOf)
        val iconBytes = fileBytes(library.sourceIconFiles, library.sourceIconBytes, sizeOf)
        val bodyBytes = fileBytes(library.chapterBodyFiles, library.chapterBodyBytes, sizeOf)
        val pageBytes = pageBytes(library.chapterPageFiles, library.chapterPageBytes, sizeOf)
        val localNovelBytes = fileBytes(library.localNovelFiles, library.localNovelBytes, sizeOf)
        val localMangaBytes = fileBytes(library.localMangaFiles, library.localMangaBytes, sizeOf)
        return StorageUsage(
            databaseBytes = databaseBytes,
            imageBytes = coverBytes + iconBytes + imageCacheBytes,
            imageCacheBytes = imageCacheBytes,
            novelCacheBytes = bodyBytes + localNovelBytes,
            mangaCacheBytes = pageBytes + localMangaBytes,
            videoBytes = videoBytes,
            clearableNovelCount = library.clearableChapterBodyCount,
            clearableMangaCount = library.clearableChapterPageCount,
            clearableImageCount = library.clearableImageCount,
            coverCount = library.coverCount,
            bookCount = library.bookCount,
            chapterCount = library.chapterCount,
            localFileCount = library.localFileCount,
            outOfLibraryBookCount = outOfLibraryBookCount,
            orphanBookDirCount = orphanBookDirCount,
            orphanBookBytes = orphanBookBytes,
        )
    }

    private fun fileBytes(
        files: List<StoredFileBytes>,
        fallback: Long,
        sizeOf: (String) -> Long?,
    ): Long {
        if (files.isEmpty()) return fallback
        return files.sumOf { file -> sizeOf(file.path) ?: file.bytes }
    }

    private fun pageBytes(
        pages: List<ChapterPageFiles>,
        fallback: Long,
        sizeOf: (String) -> Long?,
    ): Long {
        if (pages.isEmpty()) return fallback
        val actual = pages.sumOf { page ->
            val found = pagePaths(page.pathsJson).sumOf { sizeOf(it) ?: 0L }
            if (found > 0L) found else page.totalBytes
        }
        return if (actual > 0L) actual else fallback
    }
}

/** Extracts the strings of a JSON array produced for chapter_pages. */
internal fun pagePaths(raw: String): List<String> = jsonStringArray(raw)

/**
 * Reads real file sizes and deletes only regenerable caches. The Room side is
 * a [LibraryRepository]; everything filesystem-related is passed in, so the
 * aggregation can be tested on a plain JVM.
 */
class StorageUsageService(
    private val repository: LibraryRepository,
    private val databaseFiles: List<File>,
    private val videoDirs: List<File> = emptyList(),
    // Coil's durable image cache; images are covers + icons + this directory.
    private val imageCacheDir: File? = null,
    // The dir belongs to a live Coil DiskCache: it is emptied through
    // DiskCache.clear(), never by deleting files under it.
    private val clearImageLoader: (() -> Unit)? = null,
    private val sizeOf: (String) -> Long? = ::fileSizeOrNull,
    private val deleteFile: (String) -> Unit = { path -> runCatching { File(path).delete() } },
    // Video cache is owned by media3; clearing goes through its manager so
    // its database and cache index stay consistent.
    private val clearVideo: (suspend () -> Unit)? = null,
    private val purgeOutOfLibraryAction: suspend () -> Unit = {},
    private val filesDir: File? = null,
) {
    private suspend fun localBookNames(): Map<String, Set<String>> =
        localValidNames(
            repository.localBookCopyPaths() +
                repository.localBookBodyPaths() +
                repository.localBookPagePaths().flatMap(::pagePaths),
        )

    suspend fun refresh(): StorageUsage = withContext(Dispatchers.IO) {
        val library = repository.storageUsage()
        val orphans = filesDir?.let { dir ->
            inspectOrphanBookData(
                filesDir = dir,
                validBookIds = repository.bookIds().toSet(),
                validCoverIds = repository.bookCoverIds().toSet(),
                validLocalNames = localBookNames(),
            )
        } ?: OrphanBookData.Empty
        StorageUsageAggregator.aggregate(
            library = library,
            databaseBytes = databaseFiles.sumOf(::fileBytes),
            // SimpleCache keeps media spans as *.exo; its uid/index files are
            // bookkeeping, not offline video the user can reclaim.
            videoBytes = videoDirs.sumOf(::videoCacheBytes),
            imageCacheBytes = directoryBytes(imageCacheDir),
            outOfLibraryBookCount = repository.outOfLibraryBookCount(),
            orphanBookDirCount = orphans.dirCount,
            orphanBookBytes = orphans.bytes,
            sizeOf = sizeOf,
        )
    }

    /** Deletes out-of-library books and their owned rows/files. */
    suspend fun purgeOutOfLibrary(): Unit = withContext(Dispatchers.IO) {
        purgeOutOfLibraryAction()
        val dir = filesDir
        if (dir != null) {
            sweepOrphanBookData(
                filesDir = dir,
                validBookIds = repository.bookIds().toSet(),
                validCoverIds = repository.bookCoverIds().toSet(),
                validLocalNames = localBookNames(),
            )
        }
    }

    /**
     * Deletes only cache that the app can regenerate. The database bucket and
     * imported chapter bodies/pages are intentionally left untouched.
     */
    suspend fun clear(bucket: StorageBucket) = withContext(Dispatchers.IO) {
        clearStorageBucket(
            bucket = bucket,
            clearImages = {
                repository.clearImageCache().forEach(deleteFile)
            },
            clearNovelCache = {
                repository.clearRemovableChapterBodies().forEach(deleteFile)
            },
            clearMangaCache = {
                repository.clearRemovableChapterPages()
                    .flatMap(::pagePaths)
                    .forEach(deleteFile)
            },
            clearVideo = clearVideo ?: {},
            clearImageLoader = { clearImageLoader?.invoke() },
        )
    }
}

/**
 * One branch per bucket, so the video path is testable without a Room
 * database. [StorageBucket.Database] and imported local content stay.
 */
internal suspend fun clearStorageBucket(
    bucket: StorageBucket,
    clearImages: suspend () -> Unit,
    clearNovelCache: suspend () -> Unit,
    clearMangaCache: suspend () -> Unit,
    clearVideo: suspend () -> Unit,
    clearImageLoader: suspend () -> Unit = {},
) {
    when (bucket) {
        StorageBucket.Images -> {
            clearImages()
            clearImageLoader()
        }
        StorageBucket.NovelCache -> clearNovelCache()
        StorageBucket.MangaCache -> clearMangaCache()
        StorageBucket.OfflineVideo -> clearVideo()
        StorageBucket.Database -> Unit
    }
}

private fun fileSizeOrNull(path: String): Long? =
    runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()

/**
 * Sum of the real files under a cache directory; a missing dir counts as 0.
 * Coil's journal/bookkeeping files stay out: after DiskCache.clear() only the
 * journal remains, and it must not keep Images clearable with nothing to free.
 */
internal fun directoryBytes(dir: File?): Long = runCatching {
    if (dir == null || !dir.isDirectory) return@runCatching 0L
    dir.walkTopDown()
        .filter { it.isFile && it.name !in ImageCacheJournalFiles }
        .sumOf { it.length() }
}.getOrDefault(0L)

private val ImageCacheJournalFiles = setOf("journal", "journal.tmp", "journal.bkp")

private fun fileBytes(file: File): Long =
    runCatching { if (file.isFile) file.length() else 0L }.getOrDefault(0L)

/** Media3 cache spans only; the db index and uid marker are not content. */
private fun videoCacheBytes(file: File): Long = runCatching {
    if (!file.exists()) return@runCatching 0L
    file.walkTopDown()
        .filter { it.isFile && it.extension.equals("exo", ignoreCase = true) }
        .sumOf { it.length() }
}.getOrDefault(0L)
