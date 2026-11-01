package dev.mirutime.app.features.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the player chrome. The contract is NoveLA's player:
 * top overlay = episode title, current variant, HLS resolution and
 * "open in another app"; episodes switch through the media3 prev/next
 * controls; the media3 gear opens the settings panel.
 *
 * Source-scan because the app has no Compose UI test harness, and a
 * re-added top-bar settings/episode button is exactly the kind of one-line
 * regression a unit test can catch cheaply.
 *
 */
class VideoTopBarTest {

    private val root: File = generateSequence(
        File(System.getProperty("user.dir") ?: "."),
    ) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Cannot find repository root from ${System.getProperty("user.dir")}")

    private val screen = File(
        root,
        "app/src/main/java/dev/mirutime/app/features/video/ui/VideoPlayerScreen.kt",
    ).readText()

    private val settings = File(
        root,
        "app/src/main/java/dev/mirutime/app/features/video/ui/VideoSettingsSheet.kt",
    ).readText()

    @Test
    fun `top bar has no settings back or episode controls`() {
        listOf(
            "Icons.Filled.Tune",
            "Icons.AutoMirrored.Filled.ArrowBack",
            "EpisodeSheetPlayer",
            "VideoEpisodeSheet(",
            "video_episodes",
            "onOpenEpisodes",
        ).forEach { marker ->
            assertFalse("Top bar still references $marker", screen.contains(marker))
        }
    }

    @Test
    fun `top bar keeps title variant resolution and open in other`() {
        assertTrue(screen.contains("variantLabel"))
        assertTrue(screen.contains("ArrowDropDown"))
        assertTrue(screen.contains("video_source_fmt"))
        assertTrue(screen.contains("showResolution"))
        assertTrue(screen.contains("video_resolution"))
        assertTrue(screen.contains("video_open_other"))
        assertTrue(screen.contains("onVariantClick"))
    }

    @Test
    fun `media3 previous and next switch chapters directly`() {
        assertTrue(screen.contains("ChapterSwitchingPlayer"))
        assertTrue(screen.contains("seekToPrevious"))
        assertTrue(screen.contains("seekToNext"))
        assertTrue(screen.contains("setShowNextButton(true)"))
        assertTrue(screen.contains("setShowPreviousButton(true)"))
    }

    @Test
    fun `media3 gear opens the player settings sheet`() {
        assertTrue(screen.contains("exo_settings"))
        assertTrue(screen.contains("setOnClickListener"))
        assertTrue(screen.contains("settingsOpen = true"))
    }

    @Test
    fun `settings sheet follows the NoveLA section order`() {
        val order = listOf(
            "video_section_playback",
            "video_section_video",
            "video_section_gestures",
            "video_section_audio",
            "video_section_subtitles",
            "video_section_screen",
        ).map { marker ->
            val index = settings.indexOf(marker)
            assertTrue("Missing settings section $marker", index >= 0)
            index
        }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `settings rows open the track and variant choosers`() {
        listOf(
            "SettingsValueRow(",
            "onVariantClick",
            "onResolutionClick",
            "onAudioClick",
            "onSubtitlesClick",
            "VideoVariantSheet(",
        ).forEach { marker ->
            assertTrue("Missing $marker", settings.contains(marker))
        }
    }

    @Test
    fun `video chrome never falls back to AlertDialog`() {
        assertFalse(screen.contains("AlertDialog("))
        assertFalse(settings.contains("AlertDialog("))
        assertFalse(screen.contains("TrackSelectionDialogBuilder"))
        assertFalse(settings.contains("TrackSelectionDialogBuilder"))
    }
}
