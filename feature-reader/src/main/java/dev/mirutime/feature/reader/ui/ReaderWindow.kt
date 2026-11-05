package dev.mirutime.feature.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// The page keeps one padding for the whole reader: fullscreen already uses
// the space, and showing the menu must not reflow text or repaginate.
internal const val ReaderTopPadDp = 8f
internal const val ReaderBottomPadDp = 24f

internal fun shouldHideSystemBars(
    fullscreen: Boolean,
    chromeVisible: Boolean,
    panelOpen: Boolean,
): Boolean = fullscreen && !chromeVisible && !panelOpen

/**
 * The reader never puts the bars back into the hidden state it found on entry:
 * the shell always needs its header/tab-bar insets back after a pop. Kept pure
 * so ReaderWindowTest can pin the policy instead of only device-checking it.
 */
internal fun readerExitKeepsSystemBarsHidden(): Boolean = false

/**
 * Back closes an open sheet only. The menu overlay is not a modal: pressing
 * back while it is visible leaves the reader instead of just hiding the menu.
 */
internal fun readerBackClosesOverlay(panelOpen: Boolean): Boolean = panelOpen

/**
 * Synchronous exit path for the reader's own back buttons: show the bars
 * BEFORE the pop so the shell's Scaffold never measures a zero-inset window,
 * and post a second show because the last reader frame may still have a hide
 * queued. Does not touch the controller's bar behavior.
 */
fun restoreReaderBarsNow(context: Context): Boolean {
    val activity = context.findHostActivity() ?: return false
    showSystemBars(activity.window)
    return true
}

private fun showSystemBars(window: Window) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.show(WindowInsetsCompat.Type.systemBars())
    window.decorView.post {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

fun readerTopPaddingDp(fullscreen: Boolean, safeTopDp: Float): Float =
    if (fullscreen) ReaderTopPadDp else safeTopDp + ReaderTopPadDp

fun readerBottomPaddingDp(fullscreen: Boolean, safeBottomDp: Float): Float =
    if (fullscreen) ReaderBottomPadDp else safeBottomDp + ReaderBottomPadDp

/**
 * Applies ReaderStyle.fullscreen with the system bars controller. Bars come
 * back while the reader chrome or a sheet is open, and the previous visibility
 * is restored when the reader leaves. No Activity recreation.
 */
@Composable
fun ReaderFullscreenEffect(
    fullscreen: Boolean,
    chromeVisible: Boolean,
    panelOpen: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() } ?: return
    val hide = shouldHideSystemBars(fullscreen, chromeVisible, panelOpen)
    val decorView = activity.window.decorView

    DisposableEffect(activity, decorView) {
        val controller = WindowCompat.getInsetsController(activity.window, decorView)
        val previousBehavior = controller.systemBarsBehavior
        onDispose {
            val window = activity.window
            val restore = WindowCompat.getInsetsController(window, window.decorView)
            restore.systemBarsBehavior = previousBehavior
            // Always restore the bars on reader exit, no matter whether they
            // were visible when the reader opened or what the last frame did.
            // A hide queued by the reader must not win after the shell has
            // already composed underneath.
            showSystemBars(window)
        }
    }

    LaunchedEffect(hide, activity, decorView) {
        val controller = WindowCompat.getInsetsController(activity.window, decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
