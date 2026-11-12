package dev.mirutime.feature.reader.ui.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.CompatDecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MaxScale = 4f
private const val ZoomEpsilon = 0.01f
private const val SwipeWidthFraction = 0.18f
private const val SwipeSlope = 1.4f

/**
 * Zoom handle for one page. Pinch and pan belong to the tiled view; the pager
 * only needs to know whether the visible page is zoomed.
 */
internal class MangaZoomState {
    internal var view: SubsamplingScaleImageView? = null
    var zoomed by mutableStateOf(false)
        internal set

    fun reset() {
        val target = view ?: return
        if (target.isReady) target.setScaleAndCenter(target.minScale, target.center)
    }
}

/**
 * One manga page decoded from a local file with tile/region decoding.
 *
 * Gestures live inside the tiled view (NoveLA parity): SSIV consumes every
 * touch, so a Compose detector above it never sees taps. A [GestureDetector]
 * reports taps, the second tap of a double tap is suppressed so it can never
 * trigger SSIV's built-in double-tap zoom (zoom is pinch-only), and webtoon
 * pages return false from `onTouchEvent` so the vertical list keeps scrolling.
 */
@Composable
internal fun MangaTiledImage(
    file: File?,
    contentDescription: String,
    paged: Boolean,
    zoomState: MangaZoomState? = null,
    onTap: ((Float) -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onSwipe: ((Int) -> Unit)? = null,
    onZoomChanged: (Boolean) -> Unit = {},
    onRatio: ((Float) -> Unit)? = null,
    onError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // ImageSource.uri() checks the file on disk while building the source, so
    // it must not run on the main thread.
    var imageSource by remember(file) { mutableStateOf<ImageSource?>(null) }
    LaunchedEffect(file) {
        val current = file ?: return@LaunchedEffect
        imageSource = withContext(Dispatchers.IO) {
            ImageSource.uri(Uri.fromFile(current))
        }
    }
    Box(modifier.background(Color.Black)) {
        if (file == null) return@Box
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MangaScaleImageView(ctx, interactive = paged).apply {
                    setRegionDecoderFactory(
                        CompatDecoderFactory(
                            SkiaPooledImageRegionDecoder::class.java,
                            if (paged) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
                        ),
                    )
                    setMinimumTileDpi(180)
                    setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                    setMaxScale(MaxScale)
                    // Double-tap zoom is suppressed in onTouchEvent; quick
                    // scale would reintroduce it, so it stays off.
                    setQuickScaleEnabled(false)
                    onTapFraction = onTap
                    onDoubleTapAction = onDoubleTap
                    onSwipeDirection = onSwipe
                    setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            val ratio = if (sWidth > 0) sHeight.toFloat() / sWidth else 0f
                            if (ratio > 0f) onRatio?.invoke(ratio)
                        }

                        override fun onImageLoadError(e: Exception) {
                            onError()
                        }
                    })
                    setOnStateChangedListener(
                        object : SubsamplingScaleImageView.DefaultOnStateChangedListener() {
                            override fun onScaleChanged(newScale: Float, origin: Int) {
                                val zoomed = newScale > minScale + ZoomEpsilon
                                zoomState?.zoomed = zoomed
                                onZoomChanged(zoomed)
                            }

                            override fun onCenterChanged(newCenter: PointF, origin: Int) = Unit
                        },
                    )
                    this.contentDescription = contentDescription
                    zoomState?.view = this
                }
            },
            update = { view ->
                val path = file.absolutePath
                val source = imageSource
                if (source != null && view.tag != path) {
                    view.tag = path
                    view.recycle()
                    view.setImage(source)
                }
                view.contentDescription = contentDescription
                view.onTapFraction = onTap
                view.onDoubleTapAction = onDoubleTap
                view.onSwipeDirection = onSwipe
                if (paged) zoomState?.view = view
            },
            onRelease = { view ->
                if (zoomState?.view === view) {
                    zoomState?.view = null
                    zoomState?.zoomed = false
                }
                view.recycle()
            },
        )
    }
}

/**
 * NoveLA's touch model: SSIV 3.10 consumes every event and cannot disable its
 * own double-tap zoom, so the view reports taps itself and swallows the second
 * tap of a pair. Webtoon pages additionally return false so the list scrolls.
 */
private class MangaScaleImageView(
    context: Context,
    private val interactive: Boolean,
) : SubsamplingScaleImageView(context) {

    var onTapFraction: ((Float) -> Unit)? = null
    var onDoubleTapAction: (() -> Unit)? = null
    var onSwipeDirection: ((Int) -> Unit)? = null

    private var lastTapUpTime = 0L
    private var suppressGesture = false
    private var downX = 0f
    private var downY = 0f

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTapFraction?.invoke(if (width > 0) e.x / width else 0.5f)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTapAction?.invoke()
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val isSecondTap = event.pointerCount == 1 &&
                    lastTapUpTime > 0L &&
                    event.eventTime - lastTapUpTime < ViewConfiguration.getDoubleTapTimeout()
                if (isSecondTap) {
                    lastTapUpTime = 0L
                    suppressGesture = true
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // A pinch is never a double tap: let SSIV keep its state.
                suppressGesture = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (suppressGesture) {
                    suppressGesture = false
                    lastTapUpTime = 0L
                    return true
                }
                if (event.pointerCount == 1) lastTapUpTime = event.eventTime
                val dx = event.x - downX
                val dy = event.y - downY
                val zoomed = isReady && scale > minScale + ZoomEpsilon
                if (!zoomed &&
                    abs(dx) > width * SwipeWidthFraction &&
                    abs(dx) > abs(dy) * SwipeSlope
                ) {
                    onSwipeDirection?.invoke(if (dx < 0f) 1 else -1)
                }
            }

            else -> if (suppressGesture) return true
        }
        if (!interactive) return false
        return super.onTouchEvent(event)
    }
}

/**
 * Applies a manga colour filter to whatever the page draws. saveLayer keeps it
 * working on every API level (RenderEffect needs 31+).
 */
internal fun Modifier.mangaColorFilter(filter: ColorFilter?): Modifier =
    if (filter == null) {
        this
    } else {
        drawWithCache {
            val paint = Paint().apply {
                colorFilter = filter.asAndroidColorFilter()
            }
            onDrawWithContent {
                val canvas = drawContext.canvas.nativeCanvas
                val checkpoint = canvas.saveLayer(null, paint)
                drawContent()
                canvas.restoreToCount(checkpoint)
            }
        }
    }
