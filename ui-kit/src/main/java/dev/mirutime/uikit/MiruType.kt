package dev.mirutime.uikit

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.mirutime.uikit.R

// === Type system ===
// ONE family for the whole app: Noto Sans. It is the font Google designed so
// that no script renders as "tofu", and it is what Android already uses as
// the system fallback for scripts we cannot bundle (CJK, Arabic, Hebrew,
// Thai, Indic). A title in a Latin/Cyrillic face next to a CJK fallback used
// to look like two unrelated fonts; with Noto the mixed lines stay one voice.
// Bundled files cover Latin + Latin Ext (incl. Vietnamese), Cyrillic + Ext
// and Greek, instanced per weight and un-hinted (Android does not use TT
// hints), OFL licensed. Offline render stays deterministic.
//
// The previous display serif (Alegreya) is gone on purpose: one family, a
// scale of weights and sizes instead of two faces fighting for hierarchy.
private val NotoSans = FontFamily(
    Font(R.font.noto_sans_regular, FontWeight.Normal),
    Font(R.font.noto_sans_medium, FontWeight.Medium),
    Font(R.font.noto_sans_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_bold, FontWeight.Bold),
)

// Noto has tall ascenders/descenders (Cyrillic and Vietnamese diacritics
// need the room), so line height is centred inside the line box instead of
// being trimmed: a stacked accent never clips and one-line labels sit at the
// optical middle of their container in every script.
private val Centered = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    weight: FontWeight,
    size: Int,
    line: Int,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = NotoSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Centered,
)

// Scale (sp / line height). Nothing readable is below 12; 11 is for caps
// micro-labels only. The card meta/num is a deliberate 10 sp exception.
// Every screen takes its text from here — no per-screen fontSize overrides.
//
//   displaySmall  24/30  Bold      cover placeholder initial, big numbers
//   titleLarge    18/24  Bold      book / screen titles
//   titleMedium   16/22  SemiBold  section + card titles
//   titleSmall    14/20  SemiBold  small titles, list-card titles, stat values
//   bodyLarge     16/24  Regular   long reading text
//   bodyMedium    14/21  Regular   default body
//   bodySmall     12/17  Regular   captions, secondary lines
//   labelLarge    14/20  Medium    buttons, links
//   labelMedium   12/16  Medium    chips, tags, counters
//   labelSmall    11/14  SemiBold  caps eyebrows (+0.5 tracking)
val MiruTypography = Typography(
    displaySmall = style(FontWeight.Bold, 24, 30),
    titleLarge = style(FontWeight.Bold, 18, 24, tracking = -0.1),
    titleMedium = style(FontWeight.SemiBold, 16, 22),
    titleSmall = style(FontWeight.SemiBold, 14, 20),
    bodyLarge = style(FontWeight.Normal, 16, 24),
    bodyMedium = style(FontWeight.Normal, 14, 21),
    bodySmall = style(FontWeight.Normal, 12, 17),
    labelLarge = style(FontWeight.Medium, 14, 20, tracking = 0.1),
    labelMedium = style(FontWeight.Medium, 12, 16),
    labelSmall = style(FontWeight.SemiBold, 11, 14, tracking = 0.5),
)

// Card roles. The card is denser than the screen scale:
// 12.5 title, 10 meta/num, 10.5 rating. 10 sp is a deliberate exception to
// the 11 sp floor.
object MiruCardType {
    val Title = style(FontWeight.SemiBold, 12, 16).copy(
        fontSize = 12.5.sp,
        // Greedy line breaking: the first line is as full as possible; hyphen
        // dictionaries handle Latin/Cyrillic. Heading/Paragraph can leave a
        // short first line, which is exactly what the user rejected.
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.Auto,
    )
    val Meta = style(FontWeight.Normal, 10, 13)
    val Rating = style(FontWeight.SemiBold, 10, 13).copy(fontSize = 10.5.sp)
    // Source chip on a cover: one step smaller than the list meta.
    val CoverMeta = style(FontWeight.Normal, 9, 12)
    val Num = style(FontWeight.SemiBold, 10, 13)
}
