package dev.mirutime.uikit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

// === Contrast engine ===
// One rule for every text/graphic color that is derived from a picture:
//   1. Decide the backdrops it can sit on (the plates, worst-case composited
//      over black AND white photo).
//   2. Pull the color toward light/dark in HSL (hue kept) until it holds the
//      floor against ALL of them.
// Nothing derived from an image reaches the screen without passing here.
object ContrastFloor {
    const val Body = 4.5     // text, icons that carry meaning
    const val Large = 3.0    // ≥18sp text, progress fills, borders that matter
    const val Strong = 7.0   // primary reading ink on plates
}

fun hslColor(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

fun Color.toHsl(): FloatArray {
    val out = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), out)
    return out
}

// Runtime contrast guard for ink on photo frosts (chips, pills, tabs).
// WCAG floors: 4.5 for small text, 3.0 for large. A translucent frost melts
// into an unknown photo, so the ink is checked against the WORST case —
// light ink against the backing composited over white (bright photo),
// dark ink against the backing over black (dark photo). If neither white
// nor black passes, the backing densifies (+0.10 alpha per step, capped at
// 0.92) and the check repeats — the returned pair always holds the floor.
fun contrastRatio(foreground: Color, background: Color): Double =
    ColorUtils.calculateContrast(
        foreground.toArgb(),
        background.toArgb(),
    ).toDouble()

// SrcOver composite of a translucent color over an OPAQUE backdrop.
fun Color.compositedOver(opaque: Color): Color {
    val a = alpha
    return Color(
        red = red * a + opaque.red * (1 - a),
        green = green * a + opaque.green * (1 - a),
        blue = blue * a + opaque.blue * (1 - a),
        alpha = 1f,
    )
}

// Adjusts lightness (hue and saturation kept) until the color holds [floor]
// against every opaque backdrop. Falls back to white/black — whichever is
// better in the worst case — only if the hue cannot reach the floor at all.
fun Color.ensureContrastOn(backdrops: List<Color>, floor: Double = ContrastFloor.Body): Color {
    if (backdrops.isEmpty()) return this
    fun holds(c: Color) = backdrops.all { contrastRatio(c, it) >= floor }
    if (holds(this)) return this
    val meanLum = backdrops.map { ColorUtils.calculateLuminance(it.toArgb()) }.average()
    val lighten = meanLum < 0.179 // below the white/black crossover → light ink wins
    val hsl = toHsl()
    var l = hsl[2]
    val step = if (lighten) 0.02f else -0.02f
    for (i in 0 until 50) {
        l = (l + step).coerceIn(0f, 1f)
        val candidate = hslColor(hsl[0], hsl[1], l)
        if (holds(candidate)) return candidate
        if (l <= 0f || l >= 1f) break
    }
    return listOf(Color.White, Color.Black).maxBy { c -> backdrops.minOf { contrastRatio(c, it) } }
}

fun Color.ensureContrastOn(backdrop: Color, floor: Double = ContrastFloor.Body): Color =
    ensureContrastOn(listOf(backdrop), floor)

// Worst-case opaque stand-ins for a translucent plate drawn over an unknown
// photo: the plate over pure black and over pure white.
fun plateBackdrops(plates: List<Color>, alpha: Float): List<Color> =
    plates.flatMap { plate ->
        val translucent = plate.copy(alpha = alpha)
        listOf(translucent.compositedOver(Color.Black), translucent.compositedOver(Color.White))
    }
