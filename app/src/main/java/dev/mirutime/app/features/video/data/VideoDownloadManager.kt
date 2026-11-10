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
import dev.mirutime.data.library.decodeLibraryBookKey
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var pollJob: Job? = null
    private var lastSnapshotSignature: List<String> = emptyList()

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            MiruLog.event(
                "video",
                "download-init",
                "count" to downloadManager.currentDownloads.size,
            )
            // media3 starts a manager that no service hosts paused, so a queue
            // restored after process death never moves without this global
            // resume. It must not be gated on the restored rows: the index can
            // still be loading when the listener fires.
            downloadManager.resumeDownloads()
            // The queue only keeps moving while the service hosts it: media3
            // runs downloaders in the process, and without a foreground host a
            // restored queue stalls as soon as the app leaves the screen. The
            // start is gated on actual work so an idle app shows no
            // notification; a user-paused row is STOPPED and does not count.
            if (needsForegroundHost(downloadManager.currentDownloads.map { it.state })) {
                runCatching {
                    VideoDownloadService.sendResumeDownloads(appContext, foreground = true)
                }.onFailure {
                    MiruLog.w("video", "download-resume-host-failed", it)
                }
            }
            refresh()
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            MiruLog.event(
                "video",
                "download-changed",
                "id" to MiruLog.idHash(download.request.id),
                "state" to download.state,
                "stop" to download.stopReason,
                "pct" to download.percentDownloaded,
                "bytes" to download.bytesDownloaded,
                "failure" to (finalException?.javaClass?.simpleName ?: "-"),
            )
            if (finalException != null) {
                MiruLog.w("video", "download failed: ${finalException.javaClass.simpleName}", finalException)
            }
            refresh()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            MiruLog.event(
                "video",
                "download-removed",
                "id" to MiruLog.idHash(download.request.id),
                "state" to download.state,
            )
            refresh()
        }
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
    suspend fun completedVideoUri(bookUrl: String, chapterIndex: Int): String? {
        val download = withContext(Dispatchers.IO) {
            runCatching { index.getDownload(videoDownloadId(bookUrl, chapterIndex)) }.getOrNull()
        }
        return download
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.request
            ?.uri
            ?.toString()
    }

    /** Offline resolver input: the completed download for this episode, if any. */
    suspend fun completedStream(bookUrl: String, chapterIndex: Int): VideoStream? =
        allDownloads().firstNotNullOfOrNull { download ->
            if (download.state != Download.STATE_COMPLETED) return@firstNotNullOfOrNull null
            val meta = VideoDownloadData.decode(download.request.data) ?: return@firstNotNullOfOrNull null
            if (!sameBookIdentity(bookUrl, meta.bookUrl) || meta.chapterIndex != chapterIndex) {
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
        MiruLog.event(
            "video",
            "enqueue-start",
            "book" to MiruLog.idHash(bookUrl),
            "chapter" to chapterIndex,
            "mime" to (request.mimeType ?: "-"),
            "headers" to headers.size,
        )
        // One episode owns at most one row. Rows written before the
        // chapter-keyed id scheme carry the stream URL in the id, and a quality
        // or CDN-token change produced a second invisible row on the same
        // episode; both are reconciled here.
        val chapterRows = withContext(Dispatchers.IO) { chapterDownloads(bookUrl, chapterIndex) }
        val rows = chapterRows.map { ChapterDownloadRow(it.request.id, it.state) }
        val current = chapterRows.firstOrNull { it.request.id == request.id }
        if (hasConflictingDownload(rows, request.id)) {
            val blocking = chapterRows.first { it.request.id != request.id && it.state in SKIP_ENQUEUE_STATES }
            MiruLog.event(
                "video",
                "enqueue-existing",
                "chapter" to chapterIndex,
                "state" to blocking.state,
                "stop" to blocking.stopReason,
                "pct" to blocking.percentDownloaded,
            )
            return false
        }
        if (current != null && current.state == Download.STATE_STOPPED &&
            current.request.uri.toString() == stream.url
        ) {
            MiruLog.event(
                "video",
                "enqueue-existing",
                "chapter" to chapterIndex,
                "state" to current.state,
                "stop" to current.stopReason,
                "pct" to current.percentDownloaded,
            )
            // A paused episode is not a duplicate: resume it, unless the user
            // explicitly paused the whole queue. Never drop the tap silently.
            if (_pausedAll.value) return false
            withContext(Dispatchers.Main.immediate) {
                downloadManager.setStopReason(request.id, Download.STOP_REASON_NONE)
                downloadManager.resumeDownloads()
            }
            return true
        }
        if (current != null && current.state in SKIP_ENQUEUE_STATES) return false
        val obsolete = replaceableChapterDownloadIds(rows, request.id)
        if (obsolete.isNotEmpty()) {
            MiruLog.event(
                "video",
                "enqueue-replace",
                "chapter" to chapterIndex,
                "count" to obsolete.size,
            )
            withContext(Dispatchers.Main.immediate) {
                obsolete.forEach(downloadManager::removeDownload)
            }
        }

        withContext(Dispatchers.Main.immediate) {
            // Same path as NoveLA: the service owns the add and the foreground
            // start, so media3 always has a downloader host.
            runCatching {
                VideoDownloadService.sendAddDownload(
                    appContext,
                    request,
                    foreground = true,
                )
            }.onFailure { error ->
                MiruLog.w("video", "download-add-failed", error)
            }
        }
        MiruLog.event(
            "video",
            "download-enqueue",
            "book" to MiruLog.idHash(bookUrl),
            "chapter" to chapterIndex,
            "id" to MiruLog.idHash(request.id),
            "stateBefore" to (current?.state ?: -1),
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

    suspend fun pause(bookUrl: String, chapterIndex: Int) = withContext(Dispatchers.Main.immediate) {
        val id = downloadId(bookUrl, chapterIndex) ?: return@withContext
        downloadManager.setStopReason(id, STOP_REASON_PAUSED)
    }

    suspend fun resume(bookUrl: String, chapterIndex: Int) = withContext(Dispatchers.Main.immediate) {
        val id = downloadId(bookUrl, chapterIndex) ?: return@withContext
        downloadManager.setStopReason(id, Download.STOP_REASON_NONE)
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
    suspend fun cancel(bookUrl: String, chapterIndex: Int) {
        val id = downloadId(bookUrl, chapterIndex) ?: return
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
        videoDownloadId(bookUrl, chapterIndex),
        Uri.parse(stream.url),
    )
        .setMimeType(videoMimeType(stream))
        .setData(VideoDownloadData.encode(bookUrl, chapterIndex, headers))
        .build()

    /** Every row media3 holds for one episode, whatever id scheme wrote it. */
    private suspend fun chapterDownloads(bookUrl: String, chapterIndex: Int): List<Download> =
        allDownloads().filter { download ->
            val meta = VideoDownloadData.decode(download.request.data) ?: return@filter false
            sameBookIdentity(bookUrl, meta.bookUrl) && meta.chapterIndex == chapterIndex
        }

    /** The id of the row that currently owns this episode, if any. */
    private suspend fun downloadId(bookUrl: String, chapterIndex: Int): String? {
        val id = videoDownloadId(bookUrl, chapterIndex)
        val direct = withContext(Dispatchers.IO) {
            runCatching { index.getDownload(id) }.getOrNull()
        }
        if (direct != null) return id
        // Rows written before the chapter-keyed id scheme carry the stream URL.
        return chapterDownloads(bookUrl, chapterIndex).firstOrNull()?.request?.id
    }

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
        scope.launch { publishSnapshot() }
    }

    private suspend fun publishSnapshot() {
        // Listener callbacks can overlap; only the newest read may publish.
        val generation = ++refreshGeneration
        // The index holds every row including terminal states; the live manager
        // list carries current state/progress but drops COMPLETED and FAILED.
        val stored = allDownloads().mapNotNull(::toItem)
        val live = snapshotItems()
        val items = mergeDownloadItems(stored, live)
        if (generation != refreshGeneration) return
        _downloads.value = items.sortedWith(
            compareBy({ it.bookUrl }, { it.chapterIndex }),
        )
        val signature = items.map { "${it.chapterIndex}:${it.state}:${it.progress}" }
        if (signature != lastSnapshotSignature) {
            lastSnapshotSignature = signature
            MiruLog.event(
                "video",
                "download-snapshot",
                "count" to items.size,
                "states" to items.joinToString(",") {
                    "${it.chapterIndex}/${it.state}/${(it.progress * 100).toInt()}%"
                },
            )
        }
        if (items.any { it.state == VideoDownloadState.Downloading || it.state == VideoDownloadState.Queued }) {
            ensurePolling()
        } else {
            stopPolling()
        }
    }

    private suspend fun snapshotItems(): List<VideoDownloadItem> =
        withContext(Dispatchers.Main.immediate) {
            downloadManager.currentDownloads.mapNotNull(::toItem)
        }

    // media3 does not promise a callback for every progress tick; while the
    // queue is active, re-read it once a second so the list moves.
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_POLL_MS)
                publishSnapshot()
                if (_downloads.value.none {
                        it.state == VideoDownloadState.Downloading ||
                            it.state == VideoDownloadState.Queued
                    }
                ) {
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
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
        private const val PROGRESS_POLL_MS = 1_000L
        private const val STOP_REASON_PAUSED = 1
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

/** States that make an existing row win over a fresh enqueue. */
internal val SKIP_ENQUEUE_STATES = setOf(
    Download.STATE_QUEUED,
    Download.STATE_DOWNLOADING,
    Download.STATE_RESTARTING,
    Download.STATE_COMPLETED,
    Download.STATE_REMOVING,
)

/** Rows a fresh request replaces: they hold no usable episode data. */
private val REPLACEABLE_DOWNLOAD_STATES = setOf(
    Download.STATE_STOPPED,
    Download.STATE_FAILED,
)

/** Another row already owns this episode (old id scheme or a changed stream). */
internal fun hasConflictingDownload(rows: List<ChapterDownloadRow>, requestId: String): Boolean =
    rows.any { it.id != requestId && it.state in SKIP_ENQUEUE_STATES }

/** Ids of stale rows for the same episode that a fresh request must remove. */
internal fun replaceableChapterDownloadIds(
    rows: List<ChapterDownloadRow>,
    requestId: String,
): List<String> =
    rows.filter { it.id != requestId && it.state in REPLACEABLE_DOWNLOAD_STATES }
        .map { it.id }

/** The row fields the enqueue decision needs, without a media3 Download. */
internal data class ChapterDownloadRow(val id: String, val state: Int)

/**
 * True when the restored queue has work that only the foreground host keeps
 * alive. A user pause lives in a per-row stop reason (STATE_STOPPED), so it
 * does not start the service.
 */
internal fun needsForegroundHost(states: List<Int>): Boolean = states.any { state ->
    state == Download.STATE_QUEUED ||
        state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_RESTARTING
}

/**
 * One row per episode. The index keeps terminal rows that currentDownloads
 * does not report, so it is the base and live state/progress wins. A completed
 * file outranks a stray live duplicate of the same episode.
 */
internal fun mergeDownloadItems(
    stored: List<VideoDownloadItem>,
    live: List<VideoDownloadItem>,
): List<VideoDownloadItem> {
    val merged = LinkedHashMap<Pair<String, Int>, VideoDownloadItem>()
    (stored + live).forEach { item ->
        val key = item.bookUrl to item.chapterIndex
        if (merged[key]?.state != VideoDownloadState.Completed) merged[key] = item
    }
    return merged.values.toList()
}

/**
 * Book identity across the route key and the legacy raw-URL form. A key keeps
 * its install id, so two installs that expose the same URL stay apart; a row
 * written before route keys stored the raw URL and compares by URL.
 */
internal fun sameBookIdentity(bookUrl: String, storedBookUrl: String): Boolean {
    val wanted = decodeLibraryBookKey(bookUrl)
    val stored = decodeLibraryBookKey(storedBookUrl)
    return when {
        wanted != null && stored != null -> wanted == stored
        wanted != null -> wanted.url == storedBookUrl
        stored != null -> stored.url == bookUrl
        else -> bookUrl == storedBookUrl
    }
}

/** Download identity: a book URL plus the episode index, never the stream URL. */
fun videoDownloadId(bookUrl: String, chapterIndex: Int): String =
    "$bookUrl\u001F$chapterIndex"

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
        val kind = videoDownloaderKind(request.mimeType, request.uri.toString())
        MiruLog.event(
            "video",
            "downloader-create",
            "id" to MiruLog.idHash(request.id),
            "mime" to (request.mimeType ?: "-"),
            "kind" to kind,
            "headers" to headers.size,
        )
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
