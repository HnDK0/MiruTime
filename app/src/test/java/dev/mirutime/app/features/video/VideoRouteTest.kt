package dev.mirutime.app.features.video

import dev.mirutime.app.features.video.domain.VideoRoutes
import dev.mirutime.app.features.video.domain.chapterRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRouteTest {
    @Test
    fun `video book routes to the player route`() {
        assertEquals(
            "video/https%3A%2F%2Fexample.com%2Fshow%2F1/3",
            chapterRoute("https://example.com/show/1", 3, isVideo = true),
        )
    }

    @Test
    fun `text book keeps the reader route and restore flag`() {
        assertEquals(
            "reader/https%3A%2F%2Fexample.com%2Fbook/2?restore=true",
            chapterRoute("https://example.com/book", 2, isVideo = false, restoreSaved = true),
        )
    }

    @Test
    fun `route recognizer matches only video targets`() {
        assertTrue(VideoRoutes.isVideoRoute("video/anything/1"))
        assertFalse(VideoRoutes.isVideoRoute("reader/anything/1"))
        assertFalse(VideoRoutes.isVideoRoute(null))
    }
}
