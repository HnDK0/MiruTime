package dev.mirutime.app.features.settings.data

import dev.mirutime.data.library.ChapterPageFiles
import dev.mirutime.data.library.LibraryStorageUsage
import dev.mirutime.data.library.StoredFileBytes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUsageServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sizeOf: (String) -> Long? =
        { path -> File(path).takeIf { it.isFile }?.length() }

    @Test
    fun `aggregates real file sizes per category`() {
        val cover = file("cover.jpg", 300)
        val icon = file("icon.png", 400)
        val body = file("body.html", 100)
        val page = file("page.jpg", 200)
        val novel = file("novel.epub", 500)
        val manga = file("manga.cbz", 600)

        val usage = StorageUsageAggregator.aggregate(
            library = library(
                bodyBytes = 1_000,
                pageBytes = 2_000,
                coverBytes = 3_000,
                iconBytes = 4_000,
                novelBytes = 5_000,
                mangaBytes = 6_000,
                bodyFiles = listOf(StoredFileBytes(body.absolutePath, 100)),
                pageFiles = listOf(ChapterPageFiles("""["${page.absolutePath}"]""", 200)),
                coverFiles = listOf(StoredFileBytes(cover.absolutePath, 300)),
                iconFiles = listOf(StoredFileBytes(icon.absolutePath, 400)),
                novelFiles = listOf(StoredFileBytes(novel.absolutePath, 500)),
                mangaFiles = listOf(StoredFileBytes(manga.absolutePath, 600)),
            ),
            databaseBytes = 700,
            videoBytes = 800,
            sizeOf = sizeOf,
        )

        assertEquals(700L, usage.databaseBytes)
        assertEquals(800L, usage.videoBytes)
        assertEquals(700L, usage.imageBytes)
        assertEquals(600L, usage.novelCacheBytes)
        assertEquals(800L, usage.mangaCacheBytes)
        assertEquals(3_600L, usage.totalBytes)
        assertEquals(2, usage.clearableNovelCount)
        assertEquals(1, usage.clearableMangaCount)
        assertEquals(4, usage.clearableImageCount)
        assertEquals(7, usage.coverCount)
        assertEquals(8, usage.bookCount)
        assertEquals(9, usage.chapterCount)
        assertEquals(10, usage.localFileCount)
    }

    @Test
    fun `missing files fall back to database sums without crashing`() {
        val usage = StorageUsageAggregator.aggregate(
            library = library(
                bodyBytes = 1_000,
                pageBytes = 2_000,
                coverBytes = 3_000,
                iconBytes = 4_000,
                novelBytes = 5_000,
                mangaBytes = 6_000,
                bodyFiles = listOf(StoredFileBytes("/missing/body.html", 1_000)),
                pageFiles = listOf(ChapterPageFiles("""["/missing/page.jpg"]""", 2_000)),
                coverFiles = listOf(StoredFileBytes("/missing/cover.jpg", 3_000)),
                iconFiles = listOf(StoredFileBytes("/missing/icon.png", 4_000)),
                novelFiles = listOf(StoredFileBytes("/missing/novel.epub", 5_000)),
                mangaFiles = listOf(StoredFileBytes("/missing/manga.cbz", 6_000)),
            ),
            databaseBytes = 700,
            videoBytes = 0,
            sizeOf = sizeOf,
        )

        assertEquals(7_000L, usage.imageBytes)
        assertEquals(6_000L, usage.novelCacheBytes)
        assertEquals(8_000L, usage.mangaCacheBytes)
        assertEquals(21_700L, usage.totalBytes)
    }

    @Test
    fun `video clear goes through the manager callback only`() = kotlinx.coroutines.runBlocking {
        var imageClears = 0
        var videoClears = 0

        clearStorageBucket(
            bucket = StorageBucket.OfflineVideo,
            clearImages = { imageClears++ },
            clearNovelCache = {},
            clearMangaCache = {},
            clearVideo = { videoClears++ },
            clearImageLoader = {},
        )

        assertEquals(0, imageClears)
        assertEquals(1, videoClears)
    }

    @Test
    fun `images are clearable only for removable covers or the image cache`() {
        // Remote cover rows will be deleted by Clear.
        assertTrue(
            StorageUsage(imageBytes = 512, clearableImageCount = 1).isClearable(StorageBucket.Images),
        )
        // Only the disposable Coil cache holds bytes.
        assertTrue(
            StorageUsage(imageBytes = 512, imageCacheBytes = 512)
                .isClearable(StorageBucket.Images),
        )
        // Imported/user-override covers are library content: Clear would not
        // touch them, so the button must stay off.
        assertFalse(
            StorageUsage(imageBytes = 512, coverCount = 1).isClearable(StorageBucket.Images),
        )
        // A journal-only image_cache directory is no longer counted.
        assertFalse(
            StorageUsage(imageBytes = 0, imageCacheBytes = 0).isClearable(StorageBucket.Images),
        )
    }

    @Test
    fun `image cache bytes are part of the images bucket`() {
        val usage = StorageUsageAggregator.aggregate(
            library = library(
                bodyBytes = 0,
                pageBytes = 0,
                coverBytes = 0,
                iconBytes = 0,
                novelBytes = 0,
                mangaBytes = 0,
                bodyFiles = emptyList(),
                pageFiles = emptyList(),
                coverFiles = listOf(StoredFileBytes("/missing/cover.jpg", 500)),
                iconFiles = listOf(StoredFileBytes("/missing/icon.png", 400)),
                novelFiles = emptyList(),
                mangaFiles = emptyList(),
            ),
            databaseBytes = 0,
            videoBytes = 0,
            imageCacheBytes = 900,
            sizeOf = sizeOf,
        )

        assertEquals(1_800L, usage.imageBytes)
        assertEquals(900L, usage.imageCacheBytes)
    }

    @Test
    fun `image cache dir size skips coil journal bookkeeping`() {
        val dir = temp.newFolder("image_cache")
        file("image_cache/journal", 100)
        file("image_cache/journal.tmp", 50)
        file("image_cache/journal.bkp", 25)
        file("image_cache/a.jpg", 200)
        File(dir, "nested").apply { mkdirs() }
        File(temp.root, "image_cache/nested/b.jpg").apply { writeBytes(ByteArray(300)) }

        // After DiskCache.clear() only the journal remains; it must read as 0.
        assertEquals(500L, directoryBytes(dir))
        assertEquals(0L, directoryBytes(File(temp.root, "missing")))
        assertEquals(0L, directoryBytes(null))
    }

    @Test
    fun `journal-only image cache is not clearable`() {
        val dir = temp.newFolder("image_cache_journal_only")
        File(dir, "journal").writeBytes(ByteArray(100))

        assertFalse(
            StorageUsage(imageBytes = 0, imageCacheBytes = directoryBytes(dir))
                .isClearable(StorageBucket.Images),
        )
    }

    @Test
    fun `clearing images also empties the shared image loader cache`() = kotlinx.coroutines.runBlocking {
        var imageClears = 0
        var loaderClears = 0

        clearStorageBucket(
            bucket = StorageBucket.Images,
            clearImages = { imageClears++ },
            clearNovelCache = {},
            clearMangaCache = {},
            clearVideo = {},
            clearImageLoader = { loaderClears++ },
        )

        assertEquals(1, imageClears)
        assertEquals(1, loaderClears)
    }

    @Test
    fun `page path json is parsed without an android dependency`() {
        assertEquals(
            listOf("/a/1.jpg", "/a/2.jpg"),
            pagePaths("""["/a/1.jpg","/a/2.jpg"]"""),
        )
        assertEquals(0, pagePaths("not-json").size)
    }

    private fun file(name: String, bytes: Int): File =
        temp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    private fun library(
        bodyBytes: Long,
        pageBytes: Long,
        coverBytes: Long,
        iconBytes: Long,
        novelBytes: Long,
        mangaBytes: Long,
        bodyFiles: List<StoredFileBytes>,
        pageFiles: List<ChapterPageFiles>,
        coverFiles: List<StoredFileBytes>,
        iconFiles: List<StoredFileBytes>,
        novelFiles: List<StoredFileBytes>,
        mangaFiles: List<StoredFileBytes>,
    ) = LibraryStorageUsage(
        chapterBodyBytes = bodyBytes,
        chapterPageBytes = pageBytes,
        coverBytes = coverBytes,
        sourceIconBytes = iconBytes,
        localNovelBytes = novelBytes,
        localMangaBytes = mangaBytes,
        chapterBodyFiles = bodyFiles,
        chapterPageFiles = pageFiles,
        coverFiles = coverFiles,
        sourceIconFiles = iconFiles,
        localNovelFiles = novelFiles,
        localMangaFiles = mangaFiles,
        bookCount = 8,
        chapterCount = 9,
        coverCount = 7,
        localFileCount = 10,
        clearableChapterBodyCount = 2,
        clearableChapterPageCount = 1,
        clearableCoverCount = 3,
        sourceIconCount = 1,
    )
}
