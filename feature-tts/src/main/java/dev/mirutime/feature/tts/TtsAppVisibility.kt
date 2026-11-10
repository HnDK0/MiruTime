package dev.mirutime.feature.tts

/**
 * Whether the app is in front. The in-app rich player owns the screen while the
 * app is visible; the system overlay takes over only once the user leaves, so
 * the two surfaces never show the same session twice.
 *
 * Fed from ProcessLifecycleOwner in the app module: an Activity-level owner
 * would not see the app go to the background while another Activity of the same
 * app is still on screen.
 */
object TtsAppVisibility {
    private val _foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
    val foreground: kotlinx.coroutines.flow.StateFlow<Boolean> = _foreground

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }
}

private val TtsOverlayActiveStatuses = setOf(
    TtsStatus.Playing,
    TtsStatus.Loading,
    TtsStatus.Paused,
)

/**
 * The overlay exists only outside the app: in front, the in-app player is the
 * surface, and mounting both would duplicate the transport on one screen.
 */
fun ttsOverlayVisible(
    floatingEnabled: Boolean,
    permissionGranted: Boolean,
    appForeground: Boolean,
    status: TtsStatus,
): Boolean = floatingEnabled &&
    permissionGranted &&
    !appForeground &&
    status in TtsOverlayActiveStatuses
