package dev.mirutime.feature.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay and the in-app player are two surfaces of one session: exactly
 * one of them may be visible, and only with the user's grant.
 */
class TtsOverlayVisibilityTest {
    @Test
    fun `background with permission and a live session shows the overlay`() {
        assertTrue(
            ttsOverlayVisible(
                floatingEnabled = true,
                permissionGranted = true,
                appForeground = false,
                status = TtsStatus.Playing,
            ),
        )
    }

    @Test
    fun `the foreground keeps the in-app player instead of duplicating it`() {
        assertFalse(
            ttsOverlayVisible(
                floatingEnabled = true,
                permissionGranted = true,
                appForeground = true,
                status = TtsStatus.Playing,
            ),
        )
    }

    @Test
    fun `no permission keeps everything in the app`() {
        assertFalse(
            ttsOverlayVisible(
                floatingEnabled = true,
                permissionGranted = false,
                appForeground = false,
                status = TtsStatus.Playing,
            ),
        )
    }

    @Test
    fun `a disabled floating player never shows a window`() {
        assertFalse(
            ttsOverlayVisible(
                floatingEnabled = false,
                permissionGranted = true,
                appForeground = false,
                status = TtsStatus.Playing,
            ),
        )
    }

    @Test
    fun `an idle or failed session has no overlay`() {
        listOf(TtsStatus.Idle, TtsStatus.Failed).forEach { status ->
            assertFalse(
                status.name,
                ttsOverlayVisible(
                    floatingEnabled = true,
                    permissionGranted = true,
                    appForeground = false,
                    status = status,
                ),
            )
        }
    }

    @Test
    fun `a paused session keeps the overlay available`() {
        assertTrue(
            ttsOverlayVisible(
                floatingEnabled = true,
                permissionGranted = true,
                appForeground = false,
                status = TtsStatus.Paused,
            ),
        )
    }
}
