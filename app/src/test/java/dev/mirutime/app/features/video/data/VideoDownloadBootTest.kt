package dev.mirutime.app.features.video.data

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDownloadBootTest {
    @Test
    fun `restored work starts the foreground host`() {
        assertTrue(needsForegroundHost(listOf(Download.STATE_QUEUED)))
        assertTrue(needsForegroundHost(listOf(Download.STATE_DOWNLOADING)))
        assertTrue(needsForegroundHost(listOf(Download.STATE_RESTARTING)))
    }

    @Test
    fun `a paused or finished queue leaves the foreground alone`() {
        // A user pause is a per-row stop reason on a STATE_STOPPED row, and a
        // finished row must not flash a notification on every app start.
        assertFalse(needsForegroundHost(listOf(Download.STATE_STOPPED)))
        assertFalse(needsForegroundHost(listOf(Download.STATE_COMPLETED)))
        assertFalse(needsForegroundHost(listOf(Download.STATE_FAILED)))
        assertFalse(needsForegroundHost(emptyList()))
    }
}
