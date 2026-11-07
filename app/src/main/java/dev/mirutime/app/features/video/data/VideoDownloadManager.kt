@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mirutime.app.features.video.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import dev.mirutime.data.library.bookKeyUrl
import dev.mirutime.app.features.video.VideoDownloadService
import dev.mirutime.app.features.video.domain.videoMimeType
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.log.MiruLog
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** UI-facing download state; media3 integer states never reach a screen. */
enum class VideoDownloadState { Queued, Downloading, Paused, Completed, Failed }

data class VideoDownloadItem(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val state: VideoDownloadState,
    val progress: Float,
)

/** Media3-backed offline videos, independent of the text DownloadEngine. */
@OptIn(UnstableApi::class)
class VideoDownloadManager(
    context: Context,
    val videoDir: File = File(context.filesDir, DIR_NAME),
    private val client: OkHttpClient = MediaHttp.client,
) {
    private val appContext = context.applicationContext
    private val databaseProvider = ExoDatabaseProvider(appContext)
    private val cache = SimpleCache(videoDir, NoOpCacheEvictor(), databaseProvider)
    // Streaming gets its own bounded cache: playback bytes must not grow the
    // download directory and must not create download marks (5.4). It lives in
    // cacheDir and is capped at 256 MiB with LRU eviction.
    private val playbackCache = SimpleCache(
        File(appContext.cacheDir, PLAYBACK_CACHE_DIR_NAME),
        LeastRecentlyUsedCacheEvictor(PLAYBACK_CACHE_BYTES),
        NamedDatabaseProvider(appContext, PLAYBACK_CACHE_DB_NAME),
    )
    private val index = DefaultDownloadIndex(databaseProvider)
    private val executor: Executor = Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one manager used by both the service and playback. Main-thread only. */
    val downloadManager: DownloadManager = DownloadManager(
        appContext,
        index,
        VideoDownloaderFactory(cache, executor, client),
    ).apply {
        setMinRetryCount(MAX_RETRIES)
        setMaxParallelDownloads(MAX_PARALLEL_DOWNLOADS)
    }

    private val _downloads = MutableStateFlow<List<VideoDownloadItem>>(emptyList())
    val downloads: StateFlow<List<VideoDownloadItem>> = _downloads.asStateFlow()

    /** True while the user has paused the whole queue from the notification. */
    private val _pausedAll = MutableStateFlow(false)
    val pausedAll: StateFlow<Boolean> = _pausedAll.asStateFlow()
    private var refreshGeneration = 0

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) = refresh()

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (finalException != null) {
                MiruLog.w("video", "download failed: ${finalException.javaClass.simpleName}", finalException)
            }
            refresh()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) =
            refresh()
    }

    init {
        downloadManager.addListener(listener)
        refresh()
    }

    // --- playback ---------------------------------------------------------

    /**
     * The player reads through the same cache; a completed download is served
     * locally and a missing span still falls back to the network.
     *
     * `fromDownloads` selects the download cache for episodes that were fully
     * downloaded (offline playback); every other stream reads/writes the
     * bounded playback cache so streaming never inflates the download dir.
     */
    fun cacheDataSourceFactory(
        headers: Map<String, String>,
        fromDownloads: Boolean = false,
    ): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(if (fromDownloads) cache else playbackCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** The stream URI when this episode is fully downloaded, otherwise null. */
    suspend fun completedVideoUri(bookUrl: String, chapterUrl: String): String? {
        val download = withContext(Dispatchers.IO) {
            runCatching { index.getDownload(videoDownloadId(bookUrl, chapterUrl)) }.getOrNull()
        }
        return chapterUrl.takeIf { download?.state == Download.STATE_COMPLETED }
    }

    /** Offline resolver input: the completed download for this episode, if any. */
    suspend fun completedStream(bookUrl: String, chapterIndex: Int): VideoStream? =
        allDownloads().firstNotNullOfOrNull { download ->
            if (download.state != Download.STATE_COMPLETED) return@firstNotNullOfOrNull null
            val meta = VideoDownloadData.decode(download.request.data) ?: return@firstNotNullOfOrNull null
            if (bookKeyUrl(meta.bookUrl) != bookKeyUrl(bookUrl) || meta.chapterIndex != chapterIndex) {
                return@firstNotNullOfOrNull null
            }
            VideoStream(
                url = download.request.uri.toString(),
                headers = meta.headers,
                mime = download.request.mimeType,
            )
        }

    // --- mutations --------------------------------------------------------

    /** Returns false when the episode is already queued, active or complete. */
    suspend fun enqueue(
        bookUrl: String,
        chapterIndex: Int,
        stream: VideoStream,
        headers: Map<String, String>,
    ): Boolean {
        val request = createRequest(bookUrl, chapterIndex, stream, headers)
        val existing = withContext(Dispatchers.IO) {
            runCatching { index.getDownload(request.id) }.getOrNull()
        }
        if (existing != null) {
            // A paused episode is not a duplicate: resume it, unless the user
            // explicitly paused the whole queue. Never drop the tap silently.
            if (existing.state == Download.STATE_STOPPED) {
                if (_pausedAll.value) return false
                withContext(Dispatchers.Main.immediate) {
                    downloadManager.setStopReason(request.id, Download.STOP_REASON_NONE)
                    downloadManager.resumeDownloads()
                }
                return true
            }
            if (existing.state in SKIP_ENQUEUE_STATES) return false
        }

        withContext(Dispatchers.Main.immediate) {
            runCatching {
                VideoDownloadService.sendAddDownload(
                    appContext,
                    request,
                    foreground = true,
                )
            }.onFailure { error ->
                // Background-restricted starts still download while the app
                // is alive; the service re-arms them on its next start.
                MiruLog.w("video", "download service start failed", error)
                downloadManager.addDownload(request)
                downloadManager.resumeDownloads()
            }
        }
        MiruLog.event(
            "video",
            "download-enqueue",
            "book" to MiruLog.idHash(bookUrl),
            "chapter" to chapterIndex,
        )
        return true
    }

    suspend fun pauseAll() = withContext(Dispatchers.Main.immediate) {
        _pausedAll.value = true
        downloadManager.currentDownloads
            .filter { isPausableVideoState(it.state) }
            .forEach { downloadManager.setStopReason(it.request.id, STOP_REASON_PAUSED) }
    }

    suspend fun resumeAll() = withContext(Dispatchers.Main.immediate) {
        downloadManager.currentDownloads
            .filter { it.state == Download.STATE_STOPPED }
            .forEach { downloadManager.setStopReason(it.request.id, Download.STOP_REASON_NONE) }
        downloadManager.resumeDownloads()
        _pausedAll.value = false
    }

    suspend fun pause(bookUrl: String, chapterUrl: String) = withContext(Dispatchers.Main.immediate) {
        downloadManager.setStopReason(videoDownloadId(bookUrl, chapterUrl), STOP_REASON_PAUSED)
    }

    suspend fun resume(bookUrl: String, chapterUrl: String) = withContext(Dispatchers.Main.immediate) {
        downloadManager.setStopReason(videoDownloadId(bookUrl, chapterUrl), Download.STOP_REASON_NONE)
        downloadManager.resumeDownloads()
        _pausedAll.value = false
    }

    /** Resumes every paused episode of one book; the rest of the queue is untouched. */
    suspend fun resumeBook(bookUrl: String) = withContext(Dispatchers.Main.immediate) {
        downloadManager.currentDownloads
            .filter { download ->
                download.state == Download.STATE_STOPPED &&
                    VideoDownloadData.decode(download.request.data)?.bookUrl?.let(::bookKeyUrl) == bookKeyUrl(bookUrl)
            }
            .forEach { downloadManager.setStopReason(it.request.id, Download.STOP_REASON_NONE) }
        downloadManager.resumeDownloads()
        // Do not derive this from currentDownloads here: media3 moves STOPPED
        // to QUEUED asynchronously, so an immediate read could keep the
        // in-memory global pause flag on after the user resumed.
        _pausedAll.value = false
    }

    /** Cancels one episode only when it is still active; completed stays. */
    suspend fun cancel(bookUrl: String, chapterUrl: String) {
        val id = videoDownloadId(bookUrl, chapterUrl)
        val state = withContext(Dispatchers.IO) {
            runCatching { index.getDownload(id) }.getOrNull()?.state
        } ?: return
        if (!isCancellableVideoState(state)) return
        withContext(Dispatchers.Main.immediate) { downloadManager.removeDownload(id) }
    }

    /**
     * Notification Cancel: active downloads only. Completed and failed rows are
     * the user's data, not queue noise; only Settings -> Clear removes them.
     */
    suspend fun removeActive() = withContext(Dispatchers.Main.immediate) {
        downloadManager.currentDownloads
            .filter { isCancellableVideoState(it.state) }
            .forEach { downloadManager.removeDownload(it.request.id) }
        _pausedAll.value = false
    }

    /** Explicit Settings clear. The only caller allowed to drop completed rows. */
    suspend fun removeAll() = withContext(Dispatchers.Main.immediate) {
        downloadManager.removeAllDownloads()
        // Removal is asynchronous (media3 moves downloads to REMOVING first);
        // wait so a Settings refresh after Clear sees the real cache size.
        repeat(50) {
            if (allDownloads().isEmpty()) return@withContext
            delay(100)
        }
    }

    suspend fun removeForBook(bookUrl: String) = removeIds(idsForBook(bookUrl))

    suspend fun removeForBooks(bookUrls: Set<String>) {
        if (bookUrls.isEmpty()) return
        removeIds(idsForBook(bookUrls))
    }

    /** Drops downloads whose book row no longer exists. */
    suspend fun removeOrphaned(validBookUrls: Set<String>) {
        val ids = allDownloads().mapNotNull { download ->
            val meta = VideoDownloadData.decode(download.request.data) ?: return@mapNotNull null
            download.request.id.takeIf { bookKeyUrl(meta.bookUrl) !in validBookUrls }
        }
        removeIds(ids)
    }

    suspend fun removeChapters(bookUrl: String, chapterIndexes: Set<Int>) {
        if (chapterIndexes.isEmpty()) return
        val ids = allDownloads()
            .mapNotNull { download ->
                val meta = VideoDownloadData.decode(download.request.data) ?: return@mapNotNull null
                download.request.id.takeIf {
                    bookKeyUrl(meta.bookUrl) == bookKeyUrl(bookUrl) && meta.chapterIndex in chapterIndexes
                }
            }
        removeIds(ids)
    }

    private suspend fun removeIds(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            ids.forEach(downloadManager::removeDownload)
        }
    }

    // --- internals --------------------------------------------------------

    private fun createRequest(
        bookUrl: String,
        chapterIndex: Int,
        stream: VideoStream,
        headers: Map<String, String>,
    ): DownloadRequest = DownloadRequest.Builder(
        videoDownloadId(bookUrl, stream.url),
        Uri.parse(stream.url),
    )
        .setMimeType(videoMimeType(stream))
        .setData(VideoDownloadData.encode(bookUrl, chapterIndex, headers))
        .build()

    private suspend fun idsForBook(bookUrl: String): List<String> =
        allDownloads()
            .filter {
            VideoDownloadData.decode(it.request.data)?.bookUrl
                ?.let(::bookKeyUrl) == bookKeyUrl(bookUrl)
        }
            .map { it.request.id }

    private suspend fun idsForBook(bookUrls: Set<String>): List<String> =
        allDownloads()
            .filter {
            VideoDownloadData.decode(it.request.data)?.bookUrl
                ?.let(::bookKeyUrl) in bookUrls
        }
            .map { it.request.id }

    private suspend fun allDownloads(): List<Download> = withContext(Dispatchers.IO) {
        runCatching {
            index.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.download)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun refresh() {
        // Listener callbacks can overlap; only the newest read may publish.
        val generation = ++refreshGeneration
        scope.launch {
            val items = allDownloads().mapNotNull(::toItem)
            if (generation == refreshGeneration) {
                _downloads.value = items.sortedWith(
                    compareBy({ it.bookUrl }, { it.chapterIndex }),
                )
            }
        }
    }

    private fun toItem(download: Download): VideoDownloadItem? {
        if (download.state == Download.STATE_REMOVING) return null
        val meta = VideoDownloadData.decode(download.request.data) ?: return null
        val percent = download.percentDownloaded
        return VideoDownloadItem(
            bookUrl = meta.bookUrl,
            chapterIndex = meta.chapterIndex,
            chapterUrl = download.request.uri.toString(),
            state = videoDownloadState(download.state),
            progress = if (percent >= 0f) (percent / 100f).coerceIn(0f, 1f) else 0f,
        )
    }

    companion object {
        const val DIR_NAME = "video_downloads"
        const val PLAYBACK_CACHE_DIR_NAME = "video_playback_cache"
        private const val PLAYBACK_CACHE_DB_NAME = "video_playback_cache.db"
        private const val PLAYBACK_CACHE_BYTES = 256L * 1024 * 1024
        private const val MAX_PARALLEL_DOWNLOADS = 2
        private const val MAX_RETRIES = 3
        private const val STOP_REASON_PAUSED = 1
        private val SKIP_ENQUEUE_STATES = setOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
            Download.STATE_COMPLETED,
            Download.STATE_REMOVING,
        )
    }
}

/** Explicit per-row controls; only actions that the current state can honour. */
enum class VideoDownloadAction { Pause, Resume, Cancel }

internal val PAUSABLE_VIDEO_STATES = setOf(
    Download.STATE_QUEUED,
    Download.STATE_DOWNLOADING,
    Download.STATE_RESTARTING,
)

/** Cancel/clear targets: queue work only, never COMPLETED or FAILED rows. */
internal val CANCELLABLE_VIDEO_STATES = setOf(
    Download.STATE_QUEUED,
    Download.STATE_DOWNLOADING,
    Download.STATE_STOPPED,
    Download.STATE_RESTARTING,
    Download.STATE_REMOVING,
)

internal fun isPausableVideoState(state: Int): Boolean = state in PAUSABLE_VIDEO_STATES

internal fun isCancellableVideoState(state: Int): Boolean = state in CANCELLABLE_VIDEO_STATES

/** Row controls derived from the UI state: no dead buttons for finished work. */
internal fun videoDownloadActions(state: VideoDownloadState?): List<VideoDownloadAction> = when (state) {
    VideoDownloadState.Queued, VideoDownloadState.Downloading -> listOf(
        VideoDownloadAction.Pause,
        VideoDownloadAction.Cancel,
    )
    VideoDownloadState.Paused -> listOf(
        VideoDownloadAction.Resume,
        VideoDownloadAction.Cancel,
    )
    VideoDownloadState.Completed, VideoDownloadState.Failed, null -> emptyList()
}

/** Maps a media3 `Download.STATE_*` integer to the screen model. */
internal fun videoDownloadState(state: Int): VideoDownloadState = when (state) {
    Download.STATE_QUEUED -> VideoDownloadState.Queued
    Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> VideoDownloadState.Downloading
    Download.STATE_STOPPED -> VideoDownloadState.Paused
    Download.STATE_COMPLETED -> VideoDownloadState.Completed
    else -> VideoDownloadState.Failed
}

/** Download identity: a book URL plus the episode stream URL. */
fun videoDownloadId(bookUrl: String, chapterUrl: String): String =
    "$bookUrl\u001F$chapterUrl"

enum class VideoDownloaderKind { Hls, Dash, Progressive }

/** HLS/DASH/progressive selection by MIME, with a URL fallback. */
fun videoDownloaderKind(mime: String?, url: String): VideoDownloaderKind {
    val type = mime.orEmpty().trim().lowercase()
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        type in HLS_MIME_TYPES || path.endsWith(".m3u8") -> VideoDownloaderKind.Hls
        type in DASH_MIME_TYPES || path.endsWith(".mpd") -> VideoDownloaderKind.Dash
        else -> VideoDownloaderKind.Progressive
    }
}

private val HLS_MIME_TYPES = setOf(
    "hls",
    "m3u8",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
)
private val DASH_MIME_TYPES = setOf("mpd", "dash", "application/dash+xml")

internal data class VideoDownloadMeta(
    val bookUrl: String,
    val chapterIndex: Int,
    val headers: Map<String, String>,
)

/**
 * Request.data format: version line, URL-encoded book URL, chapter index and
 * one URL-encoded `key=value` header per line. Header values never contain raw
 * newlines, so the line separator is unambiguous.
 */
internal object VideoDownloadData {
    private const val VERSION = "v1"

    fun encode(bookUrl: String, chapterIndex: Int, headers: Map<String, String>): ByteArray {
        val lines = buildList {
            add(VERSION)
            add(encodePart(bookUrl))
            add(chapterIndex.toString())
            headers.toSortedMap().forEach { (key, value) ->
                add("${encodePart(key)}=${encodePart(value)}")
            }
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(data: ByteArray?): VideoDownloadMeta? {
        if (data == null || data.isEmpty()) return null
        val lines = data.toString(Charsets.UTF_8).split('\n')
        if (lines.size < 3 || lines[0] != VERSION) return null
        val bookUrl = runCatching { decodePart(lines[1]) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val chapterIndex = lines[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(3)) {
            if (line.isBlank()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            val key = runCatching { decodePart(line.substring(0, separator)) }.getOrNull()
                ?: return null
            val value = runCatching { decodePart(line.substring(separator + 1)) }.getOrNull()
                ?: return null
            headers[key] = value
        }
        return VideoDownloadMeta(bookUrl, chapterIndex, headers)
    }

    private fun encodePart(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodePart(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}

private const val PLAYBACK_DB_VERSION = 3

/** A media3 DatabaseProvider bound to a caller-chosen database file. ExoPlayer's
 *  fixed-name providers cannot back two SimpleCache instances at once. */
private class NamedDatabaseProvider(
    context: Context,
    private val name: String,
) : SQLiteOpenHelper(context.applicationContext, name, null, PLAYBACK_DB_VERSION),
    DatabaseProvider {
    override fun getWritableDatabase(): SQLiteDatabase = super.getWritableDatabase()

    override fun getReadableDatabase(): SQLiteDatabase = super.getReadableDatabase()

    override fun onCreate(db: SQLiteDatabase) = Unit

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** One downloader per request so its cached spans carry the stream headers. */
@OptIn(UnstableApi::class)
private class VideoDownloaderFactory(
    private val cache: Cache,
    private val executor: Executor,
    private val client: OkHttpClient,
) : DownloaderFactory {

    override fun createDownloader(request: DownloadRequest): Downloader {
        val headers = VideoDownloadData.decode(request.data)?.headers.orEmpty()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val item = MediaItem.Builder()
            .setUri(request.uri)
            .apply {
                request.mimeType?.let(::setMimeType)
                if (request.streamKeys.isNotEmpty()) setStreamKeys(request.streamKeys)
            }
            .build()
        return when (videoDownloaderKind(request.mimeType, request.uri.toString())) {
            VideoDownloaderKind.Hls ->
                HlsDownloader.Factory(cacheDataSourceFactory).setExecutor(executor).create(item)

            VideoDownloaderKind.Dash ->
                DashDownloader.Factory(cacheDataSourceFactory).setExecutor(executor).create(item)

            VideoDownloaderKind.Progressive ->
                ProgressiveDownloader(item, cacheDataSourceFactory, executor)
        }
    }
}
