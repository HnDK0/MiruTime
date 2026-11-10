package dev.mirutime.app.features.video.domain

import dev.mirutime.core.api.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDownloadDecisionTest {
    private val first = VideoStream(url = "https://cdn.test/1.mp4", quality = null)
    private val second = VideoStream(url = "https://cdn.test/2.mp4", quality = null)

    @Test
    fun `no streams means nothing to enqueue`() {
        assertEquals(
            VideoDownloadDecision.NoStreams,
            videoDownloadDecision(emptyList(), savedKey = null, statuses = emptyMap()),
        )
    }

    @Test
    fun `a saved url selects the same source when quality is blank`() {
        val decision = videoDownloadDecision(
            streams = listOf(first, second),
            savedKey = videoVariantKey(second),
            statuses = emptyMap(),
        )
        assertEquals(second, (decision as VideoDownloadDecision.Download).stream)
    }

    @Test
    fun `a dead saved source opens the picker`() {
        val decision = videoDownloadDecision(
            streams = listOf(first, second),
            savedKey = videoVariantKey(second),
            statuses = mapOf(second.url to VideoVariantStatus.DEAD),
        )
        assertTrue(decision is VideoDownloadDecision.OpenSheet)
    }
}
