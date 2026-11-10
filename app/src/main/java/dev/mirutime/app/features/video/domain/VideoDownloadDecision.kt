package dev.mirutime.app.features.video.domain

import dev.mirutime.core.api.VideoStream

/** Pure download decision; the composable only opens the sheet. */
sealed interface VideoDownloadDecision {
    data class Download(val stream: VideoStream) : VideoDownloadDecision
    data object OpenSheet : VideoDownloadDecision
    data object NoStreams : VideoDownloadDecision
}

fun videoDownloadDecision(
    streams: List<VideoStream>,
    savedKey: String?,
    statuses: Map<String, VideoVariantStatus>,
): VideoDownloadDecision {
    if (streams.isEmpty()) return VideoDownloadDecision.NoStreams
    val saved = savedKey?.trim().orEmpty()
    val chosen = streams.firstOrNull { videoVariantKey(it) == saved } ?: streams.first()
    return if (statuses[chosen.url] == VideoVariantStatus.DEAD) {
        VideoDownloadDecision.OpenSheet
    } else {
        VideoDownloadDecision.Download(chosen)
    }
}
