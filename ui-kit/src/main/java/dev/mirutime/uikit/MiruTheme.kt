package dev.mirutime.uikit

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme

// Theme modes. Paper/Night/Sepia carry the prototype token values verbatim;
// every color comes from a theme token. Nord/Catppuccin/Mocha mirror NoveLA's
// signature dark palettes (same hex roles). System follows the OS with
// Material dynamic color on Android 12+ and falls back to Paper/Night below.
enum class ThemeMode(
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int,
) {
    Paper(R.string.theme_paper, R.string.theme_paper_hint),
    Night(R.string.theme_night, R.string.theme_night_hint),
    Sepia(R.string.theme_sepia, R.string.theme_sepia_hint),
    Nord(R.string.theme_nord, R.string.theme_nord_hint),
    Catppuccin(R.string.theme_catppuccin, R.string.theme_catppuccin_hint),
    Mocha(R.string.theme_mocha, R.string.theme_mocha_hint),
    Tachiyomi(R.string.theme_tachiyomi, R.string.theme_tachiyomi_hint),
    GreenApple(R.string.theme_green_apple, R.string.theme_green_apple_hint),
    Lavender(R.string.theme_lavender, R.string.theme_lavender_hint),
    MidnightDusk(R.string.theme_midnight_dusk, R.string.theme_midnight_dusk_hint),
    StrawberryDaiquiri(R.string.theme_strawberry_daiquiri, R.string.theme_strawberry_daiquiri_hint),
    Tako(R.string.theme_tako, R.string.theme_tako_hint),
    TealTurquoise(R.string.theme_teal_turquoise, R.string.theme_teal_turquoise_hint),
    TidalWave(R.string.theme_tidal_wave, R.string.theme_tidal_wave_hint),
    Yotsuba(R.string.theme_yotsuba, R.string.theme_yotsuba_hint),
    Monochrome(R.string.theme_monochrome, R.string.theme_monochrome_hint),
    YinYang(R.string.theme_yinyang, R.string.theme_yinyang_hint),
    Cloudflare(R.string.theme_cloudflare, R.string.theme_cloudflare_hint),
    CottonCandy(R.string.theme_cottoncandy, R.string.theme_cottoncandy_hint),
    Doom(R.string.theme_doom, R.string.theme_doom_hint),
    Matrix(R.string.theme_matrix, R.string.theme_matrix_hint),
    Sapphire(R.string.theme_sapphire, R.string.theme_sapphire_hint),
    // Custom carries a user background image (file or link, persisted as a
    // URI string) on top of the Night palette; without one it is plain Night.
    Custom(R.string.theme_custom_bg, R.string.theme_custom_hint),
    System(R.string.theme_system, R.string.theme_system_hint),
}

@Composable
fun ThemeMode.title(): String = stringResource(titleRes)

@Composable
fun ThemeMode.hint(): String = stringResource(hintRes)

// Extra tokens that have no Material3 slot (muted text, warm rating accent,
// card accent family). No Color() literals live outside this file: cards and
// dashboard read everything through LocalMiruExtraColors. accent/onAccent
// follow each theme's secondary roles, warm follows primary.
@Immutable
data class MiruExtraColors(
    val muted: Color,
    val warm: Color,
    // Card accents: per-theme secondary roles, readable on surfaces.
    val accent: Color,
    val onAccent: Color,
    // On-cover tokens: scrim gradient end, scrim title, scrim source, dark
    // rating pill background.
    val scrim: Color,
    val onScrim: Color,
    val scrimMuted: Color,
    val pillBg: Color,
    // Photo-zone ink roles (custom background only): measured strip luma
    // decides dark-#1C1B1F vs white, mirrored in veil, shadow and chip
    // backings. Mid rides the thin global veil (0.30). Defaults = the
    // dark-strip language classic constructors had.
    val photoTop: PhotoZoneInk = DarkPhotoZone,
    val photoMid: PhotoZoneInk = DarkPhotoZone,
    val photoBottom: PhotoZoneInk = DarkPhotoZone,
)

// Cover tokens: gradient end and pill stay semi-transparent per user rule
// (readable text + element, cover shows through) — never opaque black.
// Chrome standard (header/nav on a custom image): one translucent veil so
// the element AND the background stay visible. Slightly more transparent
// than before (0.60/0.55 → 0.50/0.45) so art shows through on every theme.
private val CardScrim = Color(0xFF000000).copy(alpha = 0.5f)
private val CardOnScrim = Color(0xFFFFFFFF)
private val CardScrimMuted = Color(0xFFC9C6BD)
private val CardPillBg = Color(0xFF000000).copy(alpha = 0.45f)

// === Photo zones: adaptive ink per strip, measured once at decode ===
// BgPalette strips the top/mid/bottom thirds of the picture (luma 0..255).
// Rule (Sawyer/Spotify/HIG): bright strip (>128) → dark ink #1C1B1F + a
// LIGHT edge veil; dark strip → white ink + the black veil. The text shadow
// follows the ink (a black halo on bright art would read as dirt).
private const val PhotoBrightThreshold = 128f
private const val PillContrastFloor = 4.5
private val PhotoDarkInk = Color(0xFF1C1B1F)

@Immutable
data class PhotoZoneInk(
    // True when the strip is bright: veil turns white, ink turns #1C1B1F.
    val bright: Boolean,
    // Strong ink for bare titles/values on the photo.
    val ink: Color,
    // Softened ink for descriptors (muted layer of the same language).
    val soft: Color,
    // Ghost/dim pill backing (with alpha) that holds the 4.5 floor over
    // the post-veil strip gray — dark family on dark strips, light on bright.
    val pillBackdrop: Color,
    // Ink guaranteed against pillBackdrop at the strip's post-veil gray.
    val pillInk: Color,
    // Hairline border for ghost pills (white-ish on dark strips, dark on bright).
    val pillBorder: Color,
    // Text shadow for the strip: black on dark, light (not black) on bright.
    val shadow: Shadow,
)

// Post-veil strip gray (0..255 luma → color): white veil lifts toward
// white, black veil presses toward black.
private fun grayAt(luma: Float): Color {
    val v = luma.coerceIn(0f, 255f) / 255f
    return Color(red = v, green = v, blue = v, alpha = 1f)
}

// Builds one zone: picks ink by the measured luma, then densifies the pill
// backing (+0.10 alpha, cap 0.92) until edge ink holds 4.5 over the
// post-veil gray. Done once per palette — never per frame.
private fun buildPhotoZone(
    luma: Float,
    veilAlpha: Float,
    darkBacking: Color?, // dynamic surfaceContainerHigh (photo-derived dark family)
): PhotoZoneInk {
    // Light ink only: the per-band veil (BgPalette.veilBands) presses every
    // band until white ink holds 4.5:1, so dark-ink flipping is retired —
    // it was the source of dark text on dark cards.
    val bright = false
    // Pills composite over photo∧veil: representative veil alpha of the row,
    // so the check runs against the post-veil strip gray.
    val post = if (bright) {
        luma * (1 - veilAlpha) + 255f * veilAlpha
    } else {
        luma * (1 - veilAlpha)
    }
    val postVeil = grayAt(post)
    val ink = if (bright) PhotoDarkInk else Color(0xFFFFFFFF)
    var pill = if (bright) {
        Color(0xFFFFFFFF).copy(alpha = 0.50f)
    } else {
        (darkBacking ?: Color(0xFF000000)).copy(alpha = 0.55f)
    }
    for (i in 0 until 5) {
        if (contrastRatio(ink, pill.compositedOver(postVeil)) >= PillContrastFloor) break
        pill = pill.copy(alpha = (pill.alpha + 0.10f).coerceAtMost(0.92f))
    }
    return PhotoZoneInk(
        bright = bright,
        ink = ink,
        soft = if (bright) PhotoDarkInk.copy(alpha = 0.75f) else Color(0xFFFFFFFF).copy(alpha = 0.90f),
        pillBackdrop = pill,
        pillInk = ink,
        pillBorder = if (bright) {
            Color(0xFF000000).copy(alpha = 0.18f)
        } else {
            Color(0xFFFFFFFF).copy(alpha = 0.35f)
        },
        shadow = Shadow(
            color = if (bright) Color(0xFFFFFFFF).copy(alpha = 0.45f) else Color(0xFF000000).copy(alpha = 0.85f),
            offset = Offset(0f, 2f),
            blurRadius = 10f,
        ),
    )
}

// Default zone = dark-strip language (white ink, black family pills) — the
// look custom themes had before measurement, so failed/no-palette decodes
// and every classic-theme constructor stay put.
private val DarkPhotoZone = buildPhotoZone(luma = 60f, veilAlpha = 0.45f, darkBacking = null)

// Custom-image chrome: no solid bars — a veil behind the app header so the
// picture breathes; the mid-screen veil stays thin (0.30).
// There is NO solid bottom veil: the floating tab bar carries per-item pill
// backings instead. Bare texts carry a drop shadow (withImageShadow) and
// interactive elements sit in frosted pills (OnImageFrost).
// Top: a high plateau (0.92) holds through the WHOLE chrome — the status
// strip, the gutters beside the plate and the plate itself (the veil box is
// status bar + 6dp + plate + 6dp, so the plate bottom sits at ~92..95% of
// it) — scrolled text is erased, not dimmed. Only the tail (last 8%) eases
// out below the plate as a soft apron, never a hard band.
// Bright strips flip the SAME ramp to a white layer instead of pressing
// further with black; light-on-light needs the opposite direction.
private val DarkTopVeil = Brush.verticalGradient(
    0f to Color(0xFF000000).copy(alpha = 0.92f),
    0.92f to Color(0xFF000000).copy(alpha = 0.92f),
    0.955f to Color(0xFF000000).copy(alpha = 0.45f),
    0.985f to Color(0xFF000000).copy(alpha = 0.10f),
    1f to Color.Transparent,
)
private val LightTopVeil = Brush.verticalGradient(
    0f to Color(0xFFFFFFFF).copy(alpha = 0.92f),
    0.92f to Color(0xFFFFFFFF).copy(alpha = 0.92f),
    0.955f to Color(0xFFFFFFFF).copy(alpha = 0.45f),
    0.985f to Color(0xFFFFFFFF).copy(alpha = 0.10f),
    1f to Color.Transparent,
)
private const val BgVeilAlpha = 0.30f

// Top chrome veil (shell): page content sliding under the header fades out
// instead of reading raw in the status-bar strip. On a photo the high
// plateau (dark or light by the top strip's luma) holds to the plate bottom
// and eases out in the tail; on classic themes the same shape in the flat
// background ink — invisible against the page.
@Composable
fun miruTopVeilBrush(opaque: Boolean = false): Brush =
    when {
        !LocalMiruOnImage.current || opaque -> {
            val bg = MaterialTheme.colorScheme.background
            Brush.verticalGradient(
                0f to bg,
                0.92f to bg,
                0.955f to bg.copy(alpha = 0.45f),
                0.985f to bg.copy(alpha = 0.10f),
                1f to Color.Transparent,
            )
        }
        LocalMiruExtraColors.current.photoTop.bright -> LightTopVeil
        else -> DarkTopVeil
    }

enum class MiruPhotoZone { Top, Mid, Bottom }

// Drop shadow for bare text on the photo, per zone: black halo on dark
// strips, light (not black) on bright. No-op on classic themes.
@Composable
fun TextStyle.withImageShadow(zone: MiruPhotoZone = MiruPhotoZone.Top): TextStyle {
    if (!LocalMiruOnImage.current) return this
    val z = photoZone(zone)
    return copy(shadow = z.shadow)
}

@Composable
private fun photoZone(zone: MiruPhotoZone): PhotoZoneInk = when (zone) {
    MiruPhotoZone.Top -> LocalMiruExtraColors.current.photoTop
    MiruPhotoZone.Mid -> LocalMiruExtraColors.current.photoMid
    MiruPhotoZone.Bottom -> LocalMiruExtraColors.current.photoBottom
}

// Muted ink for bare text on the photo: adaptive per strip (dark ink on
// bright art, near-white on dark). Texts inside cards keep the theme muted—
// this is only for naked photo text.
@Composable
fun miruPhotoMuted(zone: MiruPhotoZone = MiruPhotoZone.Top): Color =
    if (LocalMiruOnImage.current) {
        photoZone(zone).soft
    } else {
        LocalMiruExtraColors.current.muted
    }

// Strong ink for bare titles/values on the photo; classic keeps onBackground.
@Composable
fun miruPhotoInk(zone: MiruPhotoZone = MiruPhotoZone.Top): Color =
    if (LocalMiruOnImage.current) {
        photoZone(zone).ink
    } else {
        MaterialTheme.colorScheme.onBackground
    }

// Ink for a SOLID fill on the photo (selected pills, badges): the theme's
// own on-color first, then white/black — whichever holds the floor. Never
// returns a failing ink: worst case picks the higher of the two ratios.
fun contrastInkFor(fill: Color, largeText: Boolean = false, preferred: List<Color> = emptyList()): Color {
    val floor = if (largeText) 3.0 else 4.5
    val candidates = (preferred + listOf(Color.White, Color.Black)).distinct()
    return candidates.firstOrNull { contrastRatio(it, fill) >= floor }
        ?: candidates.maxBy { contrastRatio(it, fill) }
}

// Frosted pill backing drawn BEHIND the content: the blur touches only the
// backing layer, text and icons stay crisp. Feathered edges melt the pill
// into the photo. The tone comes from the dynamic surface role
// (surfaceContainerHigh with alpha) — never fixed black — so frosts breathe
// the picture's palette. Shape is a moderate 12dp radius by default (full
// ovals banned). contentPadding applies inside the pill on the photo
// only, so classic layouts stay pixel-perfect. Classic: plain passthrough.
@Composable
fun OnImageFrost(
    modifier: Modifier = Modifier,
    alpha: Float = PlateAlpha,
    blur: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    // Explicit adaptive backing (photo zones): overrides the dynamic-surface
    // default so ghost pills can turn light on bright strips. Ignored on classic.
    backingColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!LocalMiruOnImage.current) {
        Box(modifier = modifier, contentAlignment = contentAlignment, content = content)
    } else {
        // Floor rule: a frost carrying text is never thinner than 86% — call
        // sites that still pass 0.55–0.60 (old haze values) are lifted here
        // so every legacy pill/label becomes a real plate.
        val base = backingColor ?: MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)
        val tone = base.copy(alpha = base.alpha.coerceAtLeast(FrostFloor))
        Box(modifier = modifier, contentAlignment = contentAlignment) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(tone, shape)
                    .blur(blur),
            )
            Box(Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

val LocalMiruOnImage = compositionLocalOf { false }

// Migration (and other text-heavy drill-ins) can opt out of the 92% photo
// plate so cards/search fields stay readable over busy art. Null keeps the
// normal theme behavior.
val LocalMiruCardSurface = compositionLocalOf<Color?> { null }

// Plates over a custom image: the theme surface at 92%. Every text color of
// the photo theme is verified against this plate composited over BOTH black
// and white (plateBackdrops), so whatever the picture does behind it, the
// ink on it holds its floor. Classic themes return the plain opaque surface.
const val PlateAlpha = 0.92f
private const val FrostFloor = 0.86f

@Composable
fun miruCardSurface(): Color {
    LocalMiruCardSurface.current?.let { return it }
    val surface = MaterialTheme.colorScheme.surface
    return if (LocalMiruOnImage.current) surface.copy(alpha = PlateAlpha) else surface
}

@Composable
fun miruCardElevation(): Dp = if (LocalMiruOnImage.current) 4.dp else 2.dp

@Composable
fun Modifier.miruCardShadow(shape: Shape): Modifier =
    this.shadow(if (LocalMiruOnImage.current) 4.dp else 2.dp, shape, clip = false)

// One look for every theme: floating plates (header, tab bar, cards) lift off
// the background with a soft shadow. On a photo the 92% plate carries the
// edge; a 4dp lift stays a separation, not a thick frame. Classic themes use
// a 2dp lift and the hairline below.
@Composable
fun miruPlateBorder(): BorderStroke? =
    if (LocalMiruOnImage.current) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

private val PaperScheme = lightColorScheme(
    background = Color(0xFFF5F3ED),
    surface = Color(0xFFFBFAF6),
    surfaceVariant = Color(0xFFEFEDE6),
    surfaceContainerLow = Color(0xFFEFEDE6),
    onBackground = Color(0xFF242622),
    onSurface = Color(0xFF242622),
    onSurfaceVariant = Color(0xFF424640),
    outline = Color(0xFFDEDED5),
    outlineVariant = Color(0xFFDEDED5),
    primary = Color(0xFF53604B),
    onPrimary = Color(0xFFFBFAF6),
    primaryContainer = Color(0xFFE6E9DF),
    onPrimaryContainer = Color(0xFF53604B),
    secondary = Color(0xFFA17C4D),
    error = Color(0xFFA65A4C),
)

private val PaperExtra = MiruExtraColors(
    muted = Color(0xFF64675F),
    warm = Color(0xFF53604B),
    accent = Color(0xFF7A5F36),
    onAccent = Color(0xFFFBFAF6),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val NightScheme = darkColorScheme(
    background = Color(0xFF171916),
    surface = Color(0xFF20231F),
    surfaceVariant = Color(0xFF292C27),
    surfaceContainerLow = Color(0xFF292C27),
    onBackground = Color(0xFFE9E9E1),
    onSurface = Color(0xFFE9E9E1),
    onSurfaceVariant = Color(0xFFD0D2C9),
    outline = Color(0xFF383C35),
    outlineVariant = Color(0xFF4A4F46),
    primary = Color(0xFFB2BCA8),
    onPrimary = Color(0xFF171916),
    primaryContainer = Color(0xFF30372E),
    onPrimaryContainer = Color(0xFFB2BCA8),
    secondary = Color(0xFFD2AD7C),
    error = Color(0xFFDB8C7E),
)

private val NightExtra = MiruExtraColors(
    muted = Color(0xFFA4A79E),
    warm = Color(0xFFB2BCA8),
    accent = Color(0xFFD2AD7C),
    onAccent = Color(0xFF171916),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val SepiaScheme = lightColorScheme(
    background = Color(0xFFEDE5D6),
    surface = Color(0xFFF6F0E4),
    surfaceVariant = Color(0xFFE9DFCC),
    surfaceContainerLow = Color(0xFFE9DFCC),
    onBackground = Color(0xFF302D27),
    onSurface = Color(0xFF302D27),
    onSurfaceVariant = Color(0xFF4A4439),
    outline = Color(0xFFD8CEBB),
    outlineVariant = Color(0xFFC3B69F),
    primary = Color(0xFF6D6952),
    onPrimary = Color(0xFFF6F0E4),
    primaryContainer = Color(0xFFE2DAC5),
    onPrimaryContainer = Color(0xFF6D6952),
    secondary = Color(0xFF9A7446),
    error = Color(0xFFA65A4C),
)

private val SepiaExtra = MiruExtraColors(
    muted = Color(0xFF6E6557),
    warm = Color(0xFF6D6952),
    accent = Color(0xFF83613A),
    onAccent = Color(0xFFF6F0E4),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

// Nord/Catppuccin/Mocha mirror NoveLA's signature dark palettes (same hex
// roles, subset our UI reads).
private val NordScheme = darkColorScheme(
    background = Color(0xFF2E3440),
    surface = Color(0xFF2E3440),
    surfaceVariant = Color(0xFF414C5C),
    surfaceContainerLow = Color(0xFF3E4756),
    onBackground = Color(0xFFECEFF4),
    onSurface = Color(0xFFECEFF4),
    onSurfaceVariant = Color(0xFFECEFF4),
    outline = Color(0xFF6D717B),
    outlineVariant = Color(0xFF90939A),
    primary = Color(0xFF88C0D0),
    onPrimary = Color(0xFF2E3440),
    primaryContainer = Color(0xFF2A5060),
    onPrimaryContainer = Color(0xFFB8E0EA),
    secondary = Color(0xFF81A1C1),
    error = Color(0xFFBF616A),
)

private val NordExtra = MiruExtraColors(
    muted = Color(0xFF9AA5B8),
    warm = Color(0xFF88C0D0),
    accent = Color(0xFF81A1C1),
    onAccent = Color(0xFF2E3440),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val CatppuccinScheme = darkColorScheme(
    background = Color(0xFF181825),
    surface = Color(0xFF181825),
    surfaceVariant = Color(0xFF1E1E2E),
    surfaceContainerLow = Color(0xFF161622),
    onBackground = Color(0xFFCDD6F4),
    onSurface = Color(0xFFCDD6F4),
    onSurfaceVariant = Color(0xFFCDD6F4),
    outline = Color(0xFFCBA6F7),
    outlineVariant = Color(0xFF585B70),
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF11111B),
    primaryContainer = Color(0xFF4A2080),
    onPrimaryContainer = Color(0xFFE8D0FF),
    secondary = Color(0xFF89B4FA),
    error = Color(0xFFF38BA8),
)

private val CatppuccinExtra = MiruExtraColors(
    muted = Color(0xFFA6ADC8),
    warm = Color(0xFFCBA6F7),
    accent = Color(0xFF89B4FA),
    onAccent = Color(0xFF001E4A),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val MochaScheme = darkColorScheme(
    background = Color(0xFF19120C),
    surface = Color(0xFF19120D),
    surfaceVariant = Color(0xFF52443C),
    surfaceContainerLow = Color(0xFF221A14),
    onBackground = Color(0xFFEFDFD6),
    onSurface = Color(0xFFF0DFD6),
    onSurfaceVariant = Color(0xFFD7C3B8),
    outline = Color(0xFF9F8D83),
    outlineVariant = Color(0xFF52443C),
    primary = Color(0xFFFFB77F),
    onPrimary = Color(0xFF4E2600),
    primaryContainer = Color(0xFF6C3A08),
    onPrimaryContainer = Color(0xFFFFDCC3),
    secondary = Color(0xFFF6BC70),
    error = Color(0xFFFFB4AB),
)

private val MochaExtra = MiruExtraColors(
    muted = Color(0xFFB8A99E),
    warm = Color(0xFFFFB77F),
    accent = Color(0xFFF6BC70),
    onAccent = Color(0xFF462B00),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

// Remaining NoveLA mirrors: signature dark palettes, same hex roles
// (subset our UI reads). Extras derive accent/warm from theme roles; muted
// is a dim tone of each theme.
private val TachiyomiScheme = darkColorScheme(
    background = Color(0xFF0C0C0C),
    surface = Color(0xFF0C0C0C),
    surfaceVariant = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF0C0C0C),
    onBackground = Color(0xFFEBEEF1),
    onSurface = Color(0xFFEBEEF1),
    onSurfaceVariant = Color(0xBFE1E6EB),
    outline = Color(0x1AE1E6EB),
    outlineVariant = Color(0x1AE1E6EB),
    primary = Color(0xFF0088FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF003A7A),
    onPrimaryContainer = Color(0xFFB0D0F0),
    secondary = Color(0xFF0088FF),
    error = Color(0xFFCF6679),
)

private val TachiyomiExtra = MiruExtraColors(
    muted = Color(0xFF8A939E),
    warm = Color(0xFF0088FF),
    accent = Color(0xFF0088FF),
    onAccent = Color(0xFF001E3D),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val GreenAppleScheme = darkColorScheme(
    background = Color(0xFF0F1510),
    surface = Color(0xFF0F1510),
    surfaceVariant = Color(0xFF3F493F),
    surfaceContainerLow = Color(0xFF181D18),
    onBackground = Color(0xFFDFE4DB),
    onSurface = Color(0xFFDFE4DB),
    onSurfaceVariant = Color(0xFFBECABC),
    outline = Color(0xFF889487),
    outlineVariant = Color(0xFF3F493F),
    primary = Color(0xFF7ADB8F),
    onPrimary = Color(0xFF003917),
    primaryContainer = Color(0xFF005320),
    onPrimaryContainer = Color(0xFF80FF9A),
    secondary = Color(0xFF7ADB8F),
    error = Color(0xFFFFB4AB),
)

private val GreenAppleExtra = MiruExtraColors(
    muted = Color(0xFF93A295),
    warm = Color(0xFF7ADB8F),
    accent = Color(0xFF7ADB8F),
    onAccent = Color(0xFF003917),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val LavenderScheme = darkColorScheme(
    background = Color(0xFF111129),
    surface = Color(0xFF111129),
    surfaceVariant = Color(0xFF3D2F6B),
    surfaceContainerLow = Color(0xFF171531),
    onBackground = Color(0xFFE7E0EC),
    onSurface = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFFCBC3D6),
    outline = Color(0xFF958E9F),
    outlineVariant = Color(0xFF4A4453),
    primary = Color(0xFFA177FF),
    onPrimary = Color(0xFF3D0090),
    primaryContainer = Color(0xFF3E2080),
    onPrimaryContainer = Color(0xFFE8D0FF),
    secondary = Color(0xFFA177FF),
    error = Color(0xFFFFB4AB),
)

private val LavenderExtra = MiruExtraColors(
    muted = Color(0xFFA89DBD),
    warm = Color(0xFFA177FF),
    accent = Color(0xFFA177FF),
    onAccent = Color(0xFF2A1656),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val MidnightDuskScheme = darkColorScheme(
    background = Color(0xFF16151D),
    surface = Color(0xFF16151D),
    surfaceVariant = Color(0xFF281624),
    surfaceContainerLow = Color(0xFF251522),
    onBackground = Color(0xFFE5E1E5),
    onSurface = Color(0xFFE5E1E5),
    onSurfaceVariant = Color(0xFFD6C1C4),
    outline = Color(0xFF9F8C8F),
    outlineVariant = Color(0xFF3D1A28),
    primary = Color(0xFFF02475),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8A0035),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFF02475),
)

private val MidnightDuskExtra = MiruExtraColors(
    muted = Color(0xFFAB9DA0),
    warm = Color(0xFFF02475),
    accent = Color(0xFFF02475),
    onAccent = Color(0xFF0D0B10),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val StrawberryDaiquiriScheme = darkColorScheme(
    background = Color(0xFF201A1A),
    surface = Color(0xFF201A1A),
    surfaceVariant = Color(0xFF322727),
    surfaceContainerLow = Color(0xFF302525),
    onBackground = Color(0xFFF7DCDD),
    onSurface = Color(0xFFF7DCDD),
    onSurfaceVariant = Color(0xFFE1BEC0),
    outline = Color(0xFFA9898B),
    outlineVariant = Color(0xFF594042),
    primary = Color(0xFFFFB2B8),
    onPrimary = Color(0xFF67001D),
    primaryContainer = Color(0xFFA00030),
    onPrimaryContainer = Color(0xFFFFD0D8),
    secondary = Color(0xFFED4A65),
    error = Color(0xFFFFB4AB),
)

private val StrawberryDaiquiriExtra = MiruExtraColors(
    muted = Color(0xFFB29597),
    warm = Color(0xFFFFB2B8),
    accent = Color(0xFFED4A65),
    onAccent = Color(0xFF201A1A),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val TakoScheme = darkColorScheme(
    background = Color(0xFF21212E),
    surface = Color(0xFF21212E),
    surfaceVariant = Color(0xFF2A2A3C),
    surfaceContainerLow = Color(0xFF21212E),
    onBackground = Color(0xFFE3E0F2),
    onSurface = Color(0xFFE3E0F2),
    onSurfaceVariant = Color(0xFFCBC4CE),
    outline = Color(0xFF958F99),
    outlineVariant = Color(0xFF3D3848),
    primary = Color(0xFFF3B375),
    onPrimary = Color(0xFF38294E),
    primaryContainer = Color(0xFF5C3D1E),
    onPrimaryContainer = Color(0xFFFFD9B0),
    secondary = Color(0xFFC8A0D8),
    error = Color(0xFFFFB4AB),
)

private val TakoExtra = MiruExtraColors(
    muted = Color(0xFFA79DA8),
    warm = Color(0xFFF3B375),
    accent = Color(0xFFC8A0D8),
    onAccent = Color(0xFF2D1040),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val TealTurquoiseScheme = darkColorScheme(
    background = Color(0xFF202125),
    surface = Color(0xFF202125),
    surfaceVariant = Color(0xFF233133),
    surfaceContainerLow = Color(0xFF222F31),
    onBackground = Color(0xFFDFDEDA),
    onSurface = Color(0xFFDFDEDA),
    onSurfaceVariant = Color(0xFFDFDEDA),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF2A4040),
    primary = Color(0xFF40E0D0),
    onPrimary = Color(0xFF004D48),
    primaryContainer = Color(0xFF004D48),
    onPrimaryContainer = Color(0xFF80F0E8),
    secondary = Color(0xFF40E0D0),
)

private val TealTurquoiseExtra = MiruExtraColors(
    muted = Color(0xFF9BA6A2),
    warm = Color(0xFF40E0D0),
    accent = Color(0xFF40E0D0),
    onAccent = Color(0xFF000000),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val TidalWaveScheme = darkColorScheme(
    background = Color(0xFF001C3B),
    surface = Color(0xFF001C3B),
    surfaceVariant = Color(0xFF082B4B),
    surfaceContainerLow = Color(0xFF072947),
    onBackground = Color(0xFFD5E3FF),
    onSurface = Color(0xFFD5E3FF),
    onSurfaceVariant = Color(0xFFBFC8CC),
    outline = Color(0xFF8A9296),
    primary = Color(0xFF5ED4FC),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFB8EAFF),
    secondary = Color(0xFF5ED4FC),
)

private val TidalWaveExtra = MiruExtraColors(
    muted = Color(0xFF93A0A6),
    warm = Color(0xFF5ED4FC),
    accent = Color(0xFF5ED4FC),
    onAccent = Color(0xFF003544),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val YotsubaScheme = darkColorScheme(
    background = Color(0xFF211A18),
    surface = Color(0xFF211A18),
    surfaceVariant = Color(0xFF332723),
    surfaceContainerLow = Color(0xFF312521),
    onBackground = Color(0xFFEDE0DD),
    onSurface = Color(0xFFEDE0DD),
    onSurfaceVariant = Color(0xFFD8C2BC),
    outline = Color(0xFFA08C87),
    outlineVariant = Color(0xFF4A3028),
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5F1600),
    primaryContainer = Color(0xFF862200),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFFFB59D),
)

private val YotsubaExtra = MiruExtraColors(
    muted = Color(0xFFAC978F),
    warm = Color(0xFFFFB59D),
    accent = Color(0xFFFFB59D),
    onAccent = Color(0xFF5F1600),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val MonochromeScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFFFFFFF),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFFFFFFFF),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
    error = Color(0xFFFFFFFF),
)

private val MonochromeExtra = MiruExtraColors(
    muted = Color(0xFF9A9A9A),
    warm = Color(0xFFFFFFFF),
    accent = Color(0xFFFFFFFF),
    onAccent = Color(0xFF000000),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val YinYangScheme = darkColorScheme(
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF313131),
    surfaceContainerLow = Color(0xFF2D2D2D),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFFD1D1D1),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF444444),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF5A5A5A),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
)

private val YinYangExtra = MiruExtraColors(
    muted = Color(0xFF9A9A9A),
    warm = Color(0xFFFFFFFF),
    accent = Color(0xFFFFFFFF),
    onAccent = Color(0xFF5A5A5A),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val CloudflareScheme = darkColorScheme(
    background = Color(0xFF1B1B22),
    surface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFF3F3F46),
    surfaceContainerLow = Color(0xFF1B1B22),
    onBackground = Color(0xFFEFF2F5),
    onSurface = Color(0xFFEFF2F5),
    onSurfaceVariant = Color(0xFFD0CDD4),
    outline = Color(0xFF8A7060),
    outlineVariant = Color(0xFF3A3028),
    primary = Color(0xFFF38020),
    onPrimary = Color(0xFF2A1800),
    primaryContainer = Color(0xFF6A3500),
    onPrimaryContainer = Color(0xFFFFE0C0),
    secondary = Color(0xFFF5A623),
)

private val CloudflareExtra = MiruExtraColors(
    muted = Color(0xFF9E928A),
    warm = Color(0xFFF38020),
    accent = Color(0xFFF5A623),
    onAccent = Color(0xFF2A1800),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val CottonCandyScheme = darkColorScheme(
    background = Color(0xFF1A1111),
    surface = Color(0xFF1A1112),
    surfaceVariant = Color(0xFF524343),
    surfaceContainerLow = Color(0xFF22191A),
    onBackground = Color(0xFFF0DEDE),
    onSurface = Color(0xFFF0DEDF),
    onSurfaceVariant = Color(0xFFD7C1C1),
    outline = Color(0xFFA08C8C),
    outlineVariant = Color(0xFF524343),
    primary = Color(0xFFFFB3B4),
    onPrimary = Color(0xFF561D21),
    primaryContainer = Color(0xFF733336),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFF80D4D8),
    error = Color(0xFFFFB4AB),
)

private val CottonCandyExtra = MiruExtraColors(
    muted = Color(0xFFA89393),
    warm = Color(0xFFFFB3B4),
    accent = Color(0xFF80D4D8),
    onAccent = Color(0xFF003739),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val DoomScheme = darkColorScheme(
    background = Color(0xFF1B1B1B),
    surface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFF303030),
    surfaceContainerLow = Color(0xFF181818),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD0CCCC),
    outline = Color(0xFF8A4040),
    outlineVariant = Color(0xFF3D2020),
    primary = Color(0xFFFF0000),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFF5C0000),
    onPrimaryContainer = Color(0xFFFFB3B3),
    secondary = Color(0xFFCC2200),
)

private val DoomExtra = MiruExtraColors(
    muted = Color(0xFF9C9A9A),
    warm = Color(0xFFFF0000),
    accent = Color(0xFFCC2200),
    onAccent = Color(0xFFFAFAFA),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val MatrixScheme = darkColorScheme(
    background = Color(0xFF111111),
    surface = Color(0xFF111111),
    surfaceVariant = Color(0xFF212121),
    surfaceContainerLow = Color(0xFF0E0E0E),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF006600),
    outlineVariant = Color(0xFF002200),
    primary = Color(0xFF00FF00),
    onPrimary = Color(0xFF001A00),
    primaryContainer = Color(0xFF003300),
    onPrimaryContainer = Color(0xFF80FF80),
    secondary = Color(0xFF00CC00),
)

private val MatrixExtra = MiruExtraColors(
    muted = Color(0xFF8F9C8F),
    warm = Color(0xFF00FF00),
    accent = Color(0xFF00CC00),
    onAccent = Color(0xFF001A00),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

private val SapphireScheme = darkColorScheme(
    background = Color(0xFF212121),
    surface = Color(0xFF212121),
    surfaceVariant = Color(0xFF424242),
    surfaceContainerLow = Color(0xFF1C2430),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF607080),
    outlineVariant = Color(0xFF2A3848),
    primary = Color(0xFF1E88E5),
    onPrimary = Color(0xFF002244),
    primaryContainer = Color(0xFF003A7A),
    onPrimaryContainer = Color(0xFFC0E0FF),
    secondary = Color(0xFF42A5F5),
)

private val SapphireExtra = MiruExtraColors(
    muted = Color(0xFF93A0AC),
    warm = Color(0xFF1E88E5),
    accent = Color(0xFF42A5F5),
    onAccent = Color(0xFF002244),
    scrim = CardScrim,
    onScrim = CardOnScrim,
    scrimMuted = CardScrimMuted,
    pillBg = CardPillBg,
)

val LocalMiruExtraColors = staticCompositionLocalOf { PaperExtra }

private data class ThemeColors(val scheme: ColorScheme, val extra: MiruExtraColors)

// === Photo theme: derived from the picture, verified against the plates ===
// Photo theme contrast rules:
//  1. Plates are ALWAYS dark: hue from the picture's dark swatch, saturation
//     clamped 0.10–0.30, lightness on a fixed ladder (0.09 … 0.34). They can
//     never become mid-tones where neither white nor black ink works.
//  2. Every ink/accent is generated from hue + lightness and then passed
//     through ensureContrastOn() against all plates, worst-case over black
//     and white photo: ink 7:1, muted/primary/error 4.5:1.
//  3. Accent fills double as text (links, "Open"), so they are held to the
//     TEXT floor, not the graphics floor.
//  4. onX colors are chosen by contrastInkFor against their own fill.
private fun photoThemeColors(p: BgPalette): ThemeColors {
    val seed = (p.surface ?: p.surfaceVariant ?: p.accent).toHsl()
    val hue = seed[0]
    val tint = seed[1].coerceIn(0.10f, 0.30f)
    val background = hslColor(hue, tint, 0.09f)
    val surface = hslColor(hue, tint, 0.14f)
    val surfaceLow = hslColor(hue, tint, 0.18f)
    val surfaceHigh = hslColor(hue, tint, 0.23f)
    val surfaceHighest = hslColor(hue, tint, 0.28f)
    val outlineVariant = hslColor(hue, tint, 0.28f)
    val outline = hslColor(hue, tint, 0.36f)
    val backs = plateBackdrops(listOf(surface, surfaceLow, surfaceHigh, surfaceHighest), PlateAlpha)

    val onSurface = hslColor(hue, 0.08f, 0.94f).ensureContrastOn(backs, ContrastFloor.Strong)
    val onSurfaceVariant = hslColor(hue, 0.08f, 0.84f).ensureContrastOn(backs, ContrastFloor.Body)
    val muted = hslColor(hue, 0.06f, 0.74f).ensureContrastOn(backs, ContrastFloor.Body)

    val a = p.accent.toHsl()
    val accentSat = a[1].coerceIn(0.40f, 0.85f)
    val primary = hslColor(a[0], accentSat, 0.70f).ensureContrastOn(backs, ContrastFloor.Body)
    val onPrimary = contrastInkFor(primary, false, listOf(Color(0xFF171916)))
    val primaryContainer = hslColor(a[0], accentSat.coerceAtMost(0.45f), 0.26f)
    val onPrimaryContainer = hslColor(a[0], 0.55f, 0.88f)
        .ensureContrastOn(listOf(primaryContainer), ContrastFloor.Strong)
    val error = Color(0xFFDB8C7E).ensureContrastOn(backs, ContrastFloor.Body)

    val scheme = NightScheme.copy(
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceLow,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surfaceLow,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHighest,
        outline = outline,
        outlineVariant = outlineVariant,
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = primary,
        onSecondary = onPrimary,
        error = error,
    )
    fun veilOf(from: Int, to: Int): Float =
        p.veilBands.subList(from.coerceAtMost(p.veilBands.size), to.coerceAtMost(p.veilBands.size))
            .maxOrNull() ?: BgVeilAlpha
    val extra = NightExtra.copy(
        accent = primary,
        onAccent = onPrimary,
        warm = primary,
        muted = muted,
        photoTop = buildPhotoZone(p.topLum, veilOf(0, 3), surfaceHigh),
        photoMid = buildPhotoZone(p.midLum, veilOf(2, 6), surfaceHigh),
        photoBottom = buildPhotoZone(p.bottomLum, veilOf(5, BgBands), surfaceHigh),
    )
    return ThemeColors(scheme, extra)
}

// Vertical black veil, one stop per measured band (screen position).
private fun photoVeilBrush(bands: List<Float>?): Brush {
    val alphas = bands ?: List(BgBands) { BgVeilAlpha }
    val n = alphas.size
    val stops = Array(n) { i -> ((i + 0.5f) / n) to Color.Black.copy(alpha = alphas[i]) }
    return Brush.verticalGradient(*stops)
}

@Composable
private fun themeColorsFor(mode: ThemeMode, bgPalette: BgPalette? = null): ThemeColors {
    // Custom with a decoded image palette: the whole theme is built from
    // the picture M3-dynamic-color style — the vibrant seed re-tints the
    // primary/accent roles AND the dark/muted swatches carry the surface
    // family and dividers. Every role falls back to its Night value on a
    // missing/bad swatch (never a crash, never unreadable).
    if (mode == ThemeMode.Custom && bgPalette != null) return photoThemeColors(bgPalette)
    return when (mode) {
        ThemeMode.Paper -> ThemeColors(PaperScheme, PaperExtra)
        ThemeMode.Night -> ThemeColors(NightScheme, NightExtra)
        ThemeMode.Sepia -> ThemeColors(SepiaScheme, SepiaExtra)
        ThemeMode.Nord -> ThemeColors(NordScheme, NordExtra)
        ThemeMode.Catppuccin -> ThemeColors(CatppuccinScheme, CatppuccinExtra)
        ThemeMode.Mocha -> ThemeColors(MochaScheme, MochaExtra)
        ThemeMode.Tachiyomi -> ThemeColors(TachiyomiScheme, TachiyomiExtra)
        ThemeMode.GreenApple -> ThemeColors(GreenAppleScheme, GreenAppleExtra)
        ThemeMode.Lavender -> ThemeColors(LavenderScheme, LavenderExtra)
        ThemeMode.MidnightDusk -> ThemeColors(MidnightDuskScheme, MidnightDuskExtra)
        ThemeMode.StrawberryDaiquiri -> ThemeColors(StrawberryDaiquiriScheme, StrawberryDaiquiriExtra)
        ThemeMode.Tako -> ThemeColors(TakoScheme, TakoExtra)
        ThemeMode.TealTurquoise -> ThemeColors(TealTurquoiseScheme, TealTurquoiseExtra)
        ThemeMode.TidalWave -> ThemeColors(TidalWaveScheme, TidalWaveExtra)
        ThemeMode.Yotsuba -> ThemeColors(YotsubaScheme, YotsubaExtra)
        ThemeMode.Monochrome -> ThemeColors(MonochromeScheme, MonochromeExtra)
        ThemeMode.YinYang -> ThemeColors(YinYangScheme, YinYangExtra)
        ThemeMode.Cloudflare -> ThemeColors(CloudflareScheme, CloudflareExtra)
        ThemeMode.CottonCandy -> ThemeColors(CottonCandyScheme, CottonCandyExtra)
        ThemeMode.Doom -> ThemeColors(DoomScheme, DoomExtra)
        ThemeMode.Matrix -> ThemeColors(MatrixScheme, MatrixExtra)
        ThemeMode.Sapphire -> ThemeColors(SapphireScheme, SapphireExtra)
        // Custom rides the Night palette; the image (if any) is drawn
        // separately behind the content.
        ThemeMode.Custom -> ThemeColors(NightScheme, NightExtra)
        ThemeMode.System -> {
            val dark = isSystemInDarkTheme()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (dark) {
                    ThemeColors(dynamicDarkColorScheme(context), NightExtra)
                } else {
                    ThemeColors(dynamicLightColorScheme(context), PaperExtra)
                }
            } else if (dark) {
                ThemeColors(NightScheme, NightExtra)
            } else {
                ThemeColors(PaperScheme, PaperExtra)
            }
        }
    }
}

@Composable
fun MiruTheme(
    mode: ThemeMode,
    background: ThemeBackground = ThemeBackground.None,
    // Decoded by the host (SAF/cache stays in :app); null falls back to the
    // flat palette while the picture loads or when it cannot be decoded.
    backgroundBitmap: ImageBitmap? = null,
    content: @Composable () -> Unit,
) {
    val bgSource = (background as? ThemeBackground.Image)?.source
    // Palette rides the same source: image colors re-tint Custom (Night
    // fallback while loading or on bad swatches — never a crash).
    val bgPalette = if (mode == ThemeMode.Custom) rememberBgPalette(bgSource) else null
    val colors = themeColorsFor(mode, bgPalette)
    val veilBrush = remember(bgPalette) { photoVeilBrush(bgPalette?.veilBands) }
    val bgBitmap = if (background is ThemeBackground.Image) backgroundBitmap else null
    CompositionLocalProvider(
        LocalMiruExtraColors provides colors.extra,
        LocalMiruOnImage provides (bgBitmap != null),
    ) {
        MaterialTheme(
            colorScheme = colors.scheme,
            typography = MiruTypography,
            shapes = MiruShapes,
        ) {
            // The full-bleed layer exists only with a real bitmap: an
            // unconditional fillMaxSize Box would also stretch size-wrapping
            // hosts (theme preview tiles nest MiruTheme inside LazyRow items).
            // A requested image that is still loading or failed to decode
            // falls back to the flat palette so transparent chrome never
            // floats over the window background.
            if (bgBitmap != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bgBitmap,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // Adaptive veil: alpha per screen band is MEASURED from the
                    // picture (BgPalette.veilBands) so light ink holds 4.5:1
                    // over its brightest pixels. Dark art keeps the thin 0.28,
                    // bright art gets up to 0.64 — and only where it is bright.
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(veilBrush),
                    )
                    content()
                }
            } else if (background is ThemeBackground.Image) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.scheme.background),
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}
