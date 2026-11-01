package dev.mirutime.app.features.video

import dev.mirutime.app.features.video.domain.VideoChapterSource
import dev.mirutime.app.features.video.domain.VideoEpisode
import dev.mirutime.app.features.video.domain.autoNextEpisode
import dev.mirutime.app.features.video.domain.hasSavedVideoQuality
import dev.mirutime.app.features.video.domain.initialVideoStreamIndex
import dev.mirutime.app.features.video.domain.isVideoResolutionLabel
import dev.mirutime.app.features.video.domain.shouldAskVideoQuality
import dev.mirutime.app.features.video.domain.videoEpisodeChoices
import dev.mirutime.app.features.video.domain.videoEpisodePosition
import dev.mirutime.app.features.video.domain.videoQualityKey
import dev.mirutime.core.api.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackRulesTest {
    private fun stream(quality: String?) = VideoStream(
        url = "https://cdn.example/$quality.mp4",
        quality = quality,
    )

    @Test
    fun `saved quality selects its stream`() {
        val streams = listOf(stream("360p"), stream("1080p"), stream("720p"))

        assertEquals(1, initialVideoStreamIndex(streams, "1080p"))
        assertTrue(hasSavedVideoQuality(streams, "1080p"))
    }

    @Test
    fun `missing or blank quality falls back to the first stream`() {
        val streams = listOf(stream("360p"), stream("720p"))

        assertEquals(0, initialVideoStreamIndex(streams, null))
        assertEquals(0, initialVideoStreamIndex(streams, "  "))
        assertEquals(0, initialVideoStreamIndex(streams, "1080p"))
        assertFalse(hasSavedVideoQuality(streams, null))
    }

    @Test
    fun `quality picker appears only for multiple streams with no saved match`() {
        val single = listOf(stream("720p"))
        val multiple = listOf(stream("360p"), stream("720p"))

        assertFalse(shouldAskVideoQuality(single, null))
        assertTrue(shouldAskVideoQuality(multiple, null))
        assertTrue(shouldAskVideoQuality(multiple, "1080p"))
        assertFalse(shouldAskVideoQuality(multiple, "720p"))
    }

    @Test
    fun `blank quality is not a usable saved key`() {
        assertEquals("", videoQualityKey(stream(null)))
        assertEquals("", videoQualityKey(stream("  ")))
        assertEquals("720p", videoQualityKey(stream(" 720p ")))
    }

    @Test
    fun `auto-next gate follows the toggle and stops at the last episode`() {
        val source = sourceAt(index = 2, episodes = 1..3)

        assertEquals(3, autoNextEpisode(source, autoNext = true)?.index)
        assertNull(autoNextEpisode(source, autoNext = false))
        assertNull(autoNextEpisode(sourceAt(index = 3, episodes = 1..3), autoNext = true))
    }

    @Test
    fun `episode picker marks exactly the current episode and keeps watched flags`() {
        val source = VideoChapterSource(
            bookUrl = "https://example.com/show",
            chapterIndex = 2,
            bookTitle = "Show",
            chapterTitle = "Episode 2",
            streams = listOf(stream("720p")),
            episodes = listOf(
                VideoEpisode(index = 1, title = "Episode 1", isRead = true),
                VideoEpisode(index = 2, title = "Episode 2", isRead = false),
                VideoEpisode(index = 3, title = "Episode 3", isRead = true),
            ),
            startPositionMs = 0,
        )

        val choices = videoEpisodeChoices(source.episodes, source.chapterIndex)

        assertEquals(listOf(1, 2, 3), choices.map { it.index })
        assertEquals(listOf(false, true, false), choices.map { it.isCurrent })
        assertEquals(listOf(true, false, true), choices.map { it.isRead })
        assertEquals(1, videoEpisodePosition(source.episodes, 2))
        assertEquals("Episode 2", source.currentEpisode?.title)
        assertEquals(1, source.previousEpisode?.index)
        assertEquals(3, source.nextEpisode?.index)
    }

    @Test
    fun `missing current episode has no current marker and position minus one`() {
        val episodes = listOf(VideoEpisode(index = 1, title = "Episode 1", isRead = false))

        assertEquals(-1, videoEpisodePosition(episodes, 9))
        assertTrue(videoEpisodeChoices(episodes, 9).none { it.isCurrent })
    }

    @Test
    fun `resolution labels group separately from voice labels`() {
        assertTrue(isVideoResolutionLabel("1080p"))
        assertTrue(isVideoResolutionLabel("720P"))
        assertTrue(isVideoResolutionLabel("4k"))
        assertTrue(isVideoResolutionLabel("fhd"))
        assertFalse(isVideoResolutionLabel("AniLiberty"))
        assertFalse(isVideoResolutionLabel(""))
    }

    private fun sourceAt(index: Int, episodes: IntRange): VideoChapterSource =
        VideoChapterSource(
            bookUrl = "https://example.com/show",
            chapterIndex = index,
            bookTitle = "Show",
            chapterTitle = "Episode $index",
            streams = listOf(stream("720p")),
            episodes = episodes.map { VideoEpisode(index = it, title = "Episode $it", isRead = false) },
            startPositionMs = 0,
        )
}
