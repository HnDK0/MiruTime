package dev.mirutime.uikit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Derived colors from the custom background image. Null means "no usable
// swatch" — callers fall back to the Night palette, never crash.
// The accent seed drives the primary/accent roles; the surface family is
// derived separately from the dark/muted swatches (nullable: each role
// falls back to its Night value when its swatch is missing or bad).
data class BgPalette(
    val accent: Color,
    val onAccent: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val muted: Color,
    val surface: Color? = null,
    val onSurface: Color? = null,
    val surfaceVariant: Color? = null,
    val onSurfaceVariant: Color? = null,
    val surfaceContainerHigh: Color? = null,
    val outline: Color? = null,
    // Average luma (0..255, 0.299R+0.587G+0.114B) of the top/middle/bottom
    // thirds of the decoded image — measured once per decode here, so
    // bare-text ink, veils and chip backings adapt per strip with zero
    // per-frame work. 0 (darkest) when unmeasured.
    val topLum: Float = 0f,
    val midLum: Float = 0f,
    val bottomLum: Float = 0f,
    // Black veil alpha per horizontal band of the SCREEN (top→bottom), sized
    // so the brightest 10% of the band's pixels are pressed to gray ≤100/255:
    // light ink then holds ≥4.5:1 over the photo anywhere. Measured once per
    // decode, the rule replaces the old flat 0.30 veil.
    val veilBands: List<Float> = List(BgBands) { 0.30f },
)

// Separate 128px Coil request (software bitmap for Palette) + in-memory
// LruCache for ~12 backgrounds. The gate uses the app's own readable text
// color (textOn) with a 3.0 floor — Palette's titleTextColor is never drawn
// anywhere, so gating on it killed good swatches for nothing.
private const val PaletteSize = 256
private const val MinContrast = 3.0
private const val LuminanceSplit = 0.179
private const val PaletteCacheSize = 12

const val BgBands = 8
// Light ink (white / 90% white) holds ≥4.5:1 over sRGB gray ≤ ~100.
private const val InkMaxLuma = 100f
private const val MinVeil = 0.28f
private const val MaxVeil = 0.64f
// The photo is drawn ContentScale.Crop on a portrait-locked phone: only the
// central slice of a wide image is ever visible, so only that slice counts.
private const val ScreenAspect = 0.46f

private val paletteCache: LruCache<String, BgPalette?> = LruCache(PaletteCacheSize)
// Failed sources: LruCache can't tell a cached null from a miss, so misses
// get their own bounded cache instead of re-decoding on every process.
private val paletteMiss: LruCache<String, Boolean> = LruCache(PaletteCacheSize)

private fun textOn(rgb: Int): Int {
    val dark = Color(0xFF171916).toArgb()
    val white = Color(0xFFFFFFFF).toArgb()
    return if (ColorUtils.calculateLuminance(rgb) > LuminanceSplit) dark else white
}

private fun pickSwatch(palette: Palette): Palette.Swatch? {
    // Vibrant family first at equal gate-passing: population-first picked the
    // dominant muted swatch and stylized art (large dull fields over saturated
    // subjects, bg4/bg6) lost its signature hue to gray-brown. Muted is only
    // the fallback when no vibrant swatch clears the readable-text gate.
    val vibrant = listOfNotNull(
        palette.vibrantSwatch,
        palette.darkVibrantSwatch,
        palette.lightVibrantSwatch,
    )
    val muted = listOfNotNull(
        palette.mutedSwatch,
        palette.darkMutedSwatch,
        palette.lightMutedSwatch,
    )
    val gate = { swatch: Palette.Swatch ->
        ColorUtils.calculateContrast(textOn(swatch.rgb), swatch.rgb) >= MinContrast
    }
    return vibrant.sortedByDescending { it.population }.firstOrNull(gate)
        ?: muted.sortedByDescending { it.population }.firstOrNull(gate)
}

private class BandStats(val p90: FloatArray, val veil: FloatArray)

// 90th-percentile luma (perceptual weights) of each horizontal band of the
// VISIBLE crop, plus the black-veil alpha that presses it to InkMaxLuma.
// p90 rather than the mean: one bright sky must not hide behind a dark rest.
private fun measureBands(bitmap: Bitmap): BandStats {
    val w = bitmap.width
    val h = bitmap.height
    val p90 = FloatArray(BgBands)
    val veil = FloatArray(BgBands) { MinVeil }
    if (w <= 0 || h <= 0) return BandStats(p90, veil)
    val aspect = w.toFloat() / h
    val visibleW = if (aspect > ScreenAspect) (w * ScreenAspect / aspect).toInt().coerceIn(1, w) else w
    val x0 = (w - visibleW) / 2
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val hist = IntArray(256)
    for (band in 0 until BgBands) {
        java.util.Arrays.fill(hist, 0)
        val y0 = h * band / BgBands
        val y1 = (h * (band + 1) / BgBands).coerceIn(y0 + 1, h)
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x0 + visibleW) {
                val c = pixels[y * w + x]
                val luma = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF))
                    .toInt().coerceIn(0, 255)
                hist[luma]++
                count++
                x += 2
            }
            y += 2
        }
        var acc = 0
        var level = 0
        val target = count * 0.9f
        while (level < 255 && acc + hist[level] < target) {
            acc += hist[level]
            level++
        }
        p90[band] = level.toFloat()
        veil[band] = if (level <= InkMaxLuma) MinVeil else (1f - InkMaxLuma / level).coerceIn(MinVeil, MaxVeil)
    }
    // Bands bleed into each other under text that straddles a border: take
    // the stronger of own / 90% of neighbours.
    val smoothed = FloatArray(BgBands) { i ->
        maxOf(veil[i], veil.getOrElse(i - 1) { 0f } * 0.9f, veil.getOrElse(i + 1) { 0f } * 0.9f)
    }
    return BandStats(p90, smoothed)
}

private suspend fun loadBgPalette(context: Context, source: String): BgPalette? {
    synchronized(paletteCache) {
        // LruCache returns null for a miss; recorded failures short-circuit
        // below without another decode.
        if (paletteCache[source] != null) return paletteCache[source]
    }
    synchronized(paletteMiss) {
        if (paletteMiss[source] != null) return null
    }
    val failed = { synchronized(paletteMiss) { paletteMiss.put(source, true) }; null }
    return try {
        val data: Any = when {
            source.startsWith("http", ignoreCase = true) -> source
            source.startsWith("content:") -> Uri.parse(source)
            else -> File(source)
        }
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(Size(PaletteSize, PaletteSize))
            .allowHardware(false)
            .build()
        val result = withContext(Dispatchers.IO) { loader.execute(request) }
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return failed()
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(16).generate()
        }
        // Band measurement rides the same decode and ALWAYS succeeds for a
        // decodable picture: a bad swatch no longer drops the veil rule.
        val bands = measureBands(bitmap)
        fun peak(from: Int, to: Int) = (from until to).maxOf { bands.p90[it] }
        val topLum = peak(0, 3)
        val midLum = peak(2, 6)
        val bottomLum = peak(5, BgBands)
        // Seed: vibrant swatch that passes the gate, else any dominant one,
        // else the Night accent. Final readability is enforced downstream in
        // the theme builder (ensureContrastOn), not here.
        val swatch = pickSwatch(palette) ?: palette.dominantSwatch
        val rgb = swatch?.rgb ?: 0xFFB2BCA8.toInt()
        val onColor = Color(textOn(rgb))
        val containerRgb = swatch?.bodyTextColor ?: rgb
        val onContainer = Color(textOn(containerRgb))
        // Hue sources for the plate family (darkest tones of the picture).
        val dark = palette.darkMutedSwatch ?: palette.darkVibrantSwatch
        val mut = palette.mutedSwatch
        val highBase = dark?.rgb ?: mut?.rgb
        val derived = BgPalette(
            topLum = topLum,
            midLum = midLum,
            bottomLum = bottomLum,
            veilBands = bands.veil.toList(),
            accent = Color(rgb),
            onAccent = onColor,
            primary = Color(rgb),
            onPrimary = onColor,
            primaryContainer = Color(containerRgb),
            onPrimaryContainer = onContainer,
            muted = Color(containerRgb),
            surface = dark?.rgb?.let { Color(it) },
            onSurface = dark?.rgb?.let { Color(textOn(it)) },
            surfaceVariant = mut?.rgb?.let { Color(it) },
            onSurfaceVariant = mut?.rgb?.let { Color(textOn(it)) },
            surfaceContainerHigh = highBase?.let {
                Color(ColorUtils.blendARGB(it, 0xFFFFFFFF.toInt(), 0.18f))
            },
            outline = dark?.titleTextColor?.let { Color(it).copy(alpha = 0.4f) },
        )
        synchronized(paletteCache) { paletteCache.put(source, derived) }
        derived
    } catch (_: Exception) {
        failed()
    }
}

@Composable
fun rememberBgPalette(source: String?): BgPalette? {
    val context = LocalContext.current.applicationContext
    var palette by remember(source) { mutableStateOf(source?.let { synchronized(paletteCache) { paletteCache[it] } }) }
    LaunchedEffect(source) {
        if (source == null) {
            palette = null
            return@LaunchedEffect
        }
        val cached = synchronized(paletteCache) { paletteCache[source] }
        if (cached != null) {
            palette = cached
            return@LaunchedEffect
        }
        palette = loadBgPalette(context, source)
    }
    return palette
}
