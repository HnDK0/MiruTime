package dev.mirutime.app.features.video.domain

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import dev.mirutime.core.api.SubtitleTrack
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.video.pickStream
import java.net.URLEncoder
import java.util.Locale

// Navigation contract for the video player. The route is a shell target (so
// the shell hides its header/tab bar); it hands the actual playback to
// VideoPlayerActivity, which owns the orientation/fullscreen window.
object VideoRoutes {
    const val Pattern = "video/{bookUrl}/{chapterIndex}"

    fun build(bookUrl: String, chapterIndex: Int): String =
        "video/${encode(bookUrl)}/$chapterIndex"

    fun isVideoRoute(route: String?): Boolean = route?.startsWith("video/") == true
}

/** Route a chapter click to the video player or the text/manga reader. */
fun chapterRoute(
    bookUrl: String,
    chapterIndex: Int,
    isVideo: Boolean,
    restoreSaved: Boolean = false,
): String = if (isVideo) {
    VideoRoutes.build(bookUrl, chapterIndex)
} else {
    "reader/${encode(bookUrl)}/$chapterIndex?restore=$restoreSaved"
}

private fun encode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

data class VideoEpisode(
    val index: Int,
    val title: String,
    val isRead: Boolean,
)

/** Everything one episode screen needs: catalog labels, streams and neighbours. */
data class VideoChapterSource(
    val bookUrl: String,
    val chapterIndex: Int,
    val bookTitle: String,
    val chapterTitle: String,
    val streams: List<VideoStream>,
    val episodes: List<VideoEpisode>,
    /** Resumed position for this episode; 0 when never watched. */
    val startPositionMs: Long,
    /** True when the streams come from a completed explicit download. */
    val offline: Boolean = false,
) {
    private val position: Int
        get() = episodes.indexOfFirst { it.index == chapterIndex }

    /** Current row in the episode picker; null when the catalog skipped it. */
    val currentEpisode: VideoEpisode?
        get() = episodes.getOrNull(position)

    val previousEpisode: VideoEpisode?
        get() = position.takeIf { it > 0 }?.let { episodes[it - 1] }

    val nextEpisode: VideoEpisode?
        get() = position.takeIf { it >= 0 && it < episodes.lastIndex }?.let { episodes[it + 1] }
}

/** One picker row: current stays the source of truth, watched comes from DB. */
data class VideoEpisodeChoice(
    val index: Int,
    val title: String,
    val isRead: Boolean,
    val isCurrent: Boolean,
)

/** Current episode position for the picker's initial scroll; -1 when absent. */
fun videoEpisodePosition(episodes: List<VideoEpisode>, chapterIndex: Int): Int =
    episodes.indexOfFirst { it.index == chapterIndex }

fun videoEpisodeChoices(
    episodes: List<VideoEpisode>,
    chapterIndex: Int,
): List<VideoEpisodeChoice> = episodes.map { episode ->
    VideoEpisodeChoice(
        index = episode.index,
        title = episode.title,
        isRead = episode.isRead,
        isCurrent = episode.index == chapterIndex,
    )
}

/** Labels that describe a resolution rather than a voice/translation variant. */
fun isVideoResolutionLabel(label: String): Boolean {
    val value = label.trim().lowercase(Locale.ROOT)
    return value.matches(Regex("""\d{3,4}p""")) ||
        value in setOf("sd", "hd", "fhd", "uhd", "2k", "4k", "8k")
}

sealed interface VideoLoadResult {
    data object Loading : VideoLoadResult

    data class Ready(val source: VideoChapterSource) : VideoLoadResult

    /** Resolver answered with no streams; a state, not a failure. */
    data object Empty : VideoLoadResult

    /**
     * `message` is the plugin's own text when it has one; null means the UI
     * should show its localized generic line.
     */
    data class Error(val message: String? = null) : VideoLoadResult
}

// --- stream selection / media mapping -------------------------------------

/** Exact quality when present, otherwise the source order (core semantics). */
fun selectVideoStream(streams: List<VideoStream>, quality: String?): VideoStream? =
    pickStream(streams, quality)

/** Saved per-book key: quality when known, otherwise the stable stream URL. */
fun videoVariantKey(stream: VideoStream): String =
    stream.quality?.trim()?.takeIf { it.isNotBlank() } ?: stream.url.trim()

fun videoStreamLabel(stream: VideoStream): String =
    stream.quality?.takeIf { it.isNotBlank() }
        ?: stream.mime?.takeIf { it.isNotBlank() }
        ?: ""

// --- per-book quality choice ------------------------------------------------

/** The saved per-book label; blank quality is not a usable choice. */
fun videoQualityKey(stream: VideoStream): String =
    stream.quality?.trim()?.takeIf { it.isNotBlank() }.orEmpty()

/** Saved quality wins; otherwise the first source (core order). */
fun initialVideoStreamIndex(streams: List<VideoStream>, savedQuality: String?): Int {
    if (streams.isEmpty()) return 0
    val saved = savedQuality?.trim().orEmpty()
    if (saved.isBlank()) return 0
    val index = streams.indexOfFirst { videoVariantKey(it) == saved }
    return index.coerceAtLeast(0)
}

fun hasSavedVideoQuality(streams: List<VideoStream>, savedQuality: String?): Boolean {
    val saved = savedQuality?.trim().orEmpty()
    if (saved.isBlank()) return false
    return streams.any { videoVariantKey(it) == saved }
}

/**
 * Ask only when there is something to choose and the current label is not
 * already saved for this book.
 */
fun shouldAskVideoQuality(streams: List<VideoStream>, savedQuality: String?): Boolean =
    streams.size > 1 && !hasSavedVideoQuality(streams, savedQuality)

/** Auto-next gate: the toggle decides whether STATE_ENDED advances. */
fun autoNextEpisode(source: VideoChapterSource, autoNext: Boolean): VideoEpisode? =
    if (autoNext) source.nextEpisode else null

// --- variants and liveness -------------------------------------------------

/** Probe result for one stream URL. */
enum class VideoVariantStatus { OK, DEAD, UNKNOWN }

/**
 * One row of the quality/variant chooser: the original index keeps the saved
 * quality key and selection mapping stable, `dead` drives the badge.
 */
data class VideoVariantChoice(
    val index: Int,
    val stream: VideoStream,
    val label: String,
    val dead: Boolean,
)

fun videoVariantChoices(
    streams: List<VideoStream>,
    statuses: Map<String, VideoVariantStatus>,
): List<VideoVariantChoice> = streams.mapIndexed { index, stream ->
    VideoVariantChoice(
        index = index,
        stream = stream,
        label = videoStreamLabel(stream),
        dead = statuses[stream.url] == VideoVariantStatus.DEAD,
    )
}

/**
 * URLs to probe now. Cached OK/UNKNOWN are not rechecked (a probe is a real
 * network call). DEAD is rechecked only when the chooser opens, so a revived
 * CDN signature can lose its badge without probing on every recomposition.
 */
fun pendingVariantProbes(
    streams: List<VideoStream>,
    statuses: Map<String, VideoVariantStatus>,
    includeDead: Boolean,
): List<VideoStream> = streams
    .distinctBy { it.url }
    .filter { stream ->
        when (statuses[stream.url]) {
            null -> true
            VideoVariantStatus.DEAD -> includeDead
            VideoVariantStatus.OK, VideoVariantStatus.UNKNOWN -> false
        }
    }

// --- playback errors -------------------------------------------------------

/** media3 error-code ranges: 2xxx/3xxx are source, 4xxx are codec. */
enum class VideoPlaybackErrorKind { Source, Codec, Other }

fun videoPlaybackErrorKind(errorCode: Int): VideoPlaybackErrorKind = when (errorCode) {
    in 2_000..3_999 -> VideoPlaybackErrorKind.Source
    in 4_000..4_999 -> VideoPlaybackErrorKind.Codec
    else -> VideoPlaybackErrorKind.Other
}

/** Only decoder init/decoding failures are worth one software-decoder retry. */
fun isDecoderFallbackError(errorCode: Int): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

/** Resume at the error position; fall back to the session position if unknown. */
fun videoRestartPositionMs(positionMs: Long, fallbackMs: Long): Long =
    positionMs.takeIf { it > 0 } ?: fallbackMs.coerceAtLeast(0)

/** External-player MIME: HLS needs the HLS type, everything else is the video wildcard. */
fun videoExternalMimeType(stream: VideoStream): String =
    if (videoMimeType(stream) == MimeTypes.APPLICATION_M3U8) {
        MimeTypes.APPLICATION_M3U8
    } else {
        "video/*"
    }

/** Player hint: HLS/DASH/progressive; null lets media3 infer from the URL. */
fun videoMimeType(stream: VideoStream): String? {
    val hint = stream.mime?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val fromHint = when (hint) {
        "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" ->
            MimeTypes.APPLICATION_M3U8
        "mp4", "video/mp4" -> MimeTypes.VIDEO_MP4
        "mpd", "dash", "application/dash+xml" -> MimeTypes.APPLICATION_MPD
        else -> null
    }
    if (fromHint != null) return fromHint
    if (hint.startsWith("video/") || hint.startsWith("application/")) return stream.mime
    val path = stream.url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

fun subtitleMimeType(track: SubtitleTrack): String =
    if (track.url.substringBefore('?').substringBefore('#').endsWith(".vtt", ignoreCase = true)) {
        MimeTypes.TEXT_VTT
    } else {
        MimeTypes.APPLICATION_SUBRIP
    }

/**
 * Stream headers are per-source and authoritative; the plugin host defaults
 * (Referer/UA/cookies from PluginGraph) fill the gaps only.
 */
fun videoRequestHeaders(
    pluginHeaders: Map<String, String>,
    streamHeaders: Map<String, String>,
): Map<String, String> {
    if (pluginHeaders.isEmpty()) return streamHeaders
    val merged = LinkedHashMap<String, String>(pluginHeaders.size + streamHeaders.size)
    merged.putAll(pluginHeaders)
    merged.putAll(streamHeaders)
    return merged
}

// --- progress mapping ------------------------------------------------------

/** 95% of a known duration means the episode is watched. */
fun videoShouldMarkRead(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs > 0 && positionMs * 100 >= durationMs * 95

/** Resume mid-episode; a watched episode starts over instead of at its tail. */
fun videoResumePositionMs(savedPositionMs: Long, savedDurationMs: Long): Long {
    if (savedPositionMs <= 0) return 0
    if (videoShouldMarkRead(savedPositionMs, savedDurationMs)) return 0
    return savedPositionMs
}
