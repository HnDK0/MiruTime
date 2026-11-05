package dev.mirutime.uikit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// === Shape scale ===
// Rule: nested radius = outer radius − padding where practical. Card 12 and
// Inner 14 are separate families (plates/tiles), so the arithmetic is not
// universal; a card (12) with an 8 dp padding holds a cover with a 6 dp
// radius, which reads as one concentric family.
object MiruRadius {
    val Badge = 6.dp      // tiny tags on covers
    val Split = 4.dp      // split-progress and small plates
    val Cover = 6.dp      // book covers, inner thumbnails
    val Control = 12.dp   // buttons, outlined fields and compact controls
    val Inner = 14.dp     // tiles nested inside a card
    val Card = 12.dp      // cards, plates, panels
    val Plate = 24.dp     // floating chrome (header, tab bar)
    val Sheet = 28.dp     // bottom sheets (top corners)
}

// === Spacing scale ===
// One 4dp-based ladder for every gap in the app. Screens never invent a
// 10 / 14 / 20 — they pick the step that matches the relationship:
//   Xs  4   icon <-> text, title <-> subtitle inside one block
//   S   8   siblings inside a block (buttons in a row, tag rows)
//   M  12   inner block padding of compact items, gutter between cards in a row
//   L  16   screen edge, card padding, gap BETWEEN cards in a column
//   Xl 24   gap between page sections (a section = a card or a group of cards)
object MiruSpace {
    val Xxs = 2.dp
    val Xs = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val Xl = 24.dp
    // Card grid gutter: 4 dp on both axes.
    val CardGap = Xs
}

// Cover gutter: 4dp inside the card and 4dp on the other side of the grid
// gap, so neighbouring covers sit 8dp apart. No border.
val CoverInset = 4.dp

// === Size grid ===
// Rule: visual size and touch size are separate. Anything tappable has a
// 48dp hit area; the visible shape may be smaller (40 / 36) but never the
// hit box. Text never goes below 12sp (labels 11sp, caps only).
object MiruSize {
    val Touch = 48.dp
    val Control = 40.dp      // round icon buttons (visual), 12dp radius
    val Chip = 28.dp         // filter chips (visual; at least 48dp touch)
    val SplitProgress = 56.dp // split-progress pill (chapters read / total)
    val SplitProgressHeight = 16.dp
    val Button = 44.dp       // secondary buttons / plate pills
    val ButtonPrimary = 52.dp // the one loud action of a screen
    val Row = 56.dp          // single-line list rows (tools, settings)
    val ScreenPad = MiruSpace.L
    val Gap = MiruSpace.L    // between stacked cards
    val SectionGap = MiruSpace.Xl
    val CardPad = MiruSpace.L
    // Book detail cover (2:3). Bigger than a library thumb, the page's hero.
    val DetailCoverWidth = 96.dp
    val DetailCoverHeight = 144.dp
    // Catalogue/global-search tile width (CatalogTile in a LazyRow).
    val Tile = 140.dp
    val IconSmall = 14.dp
    val Icon = 22.dp
    val Indicator = 16.dp
    val Stroke = 1.dp
    val ContentMaxWidth = 600.dp
    const val SheetMaxFraction = 0.7f
}

// Legacy names kept so existing imports keep compiling.
val CardCorner = MiruRadius.Card
val SheetCorner = MiruRadius.Sheet

val MiruShapes = Shapes(
    extraSmall = RoundedCornerShape(MiruRadius.Cover),
    small = RoundedCornerShape(MiruRadius.Control),
    medium = RoundedCornerShape(MiruRadius.Card),
    large = RoundedCornerShape(MiruRadius.Plate),
    extraLarge = RoundedCornerShape(topStart = MiruRadius.Sheet, topEnd = MiruRadius.Sheet),
)
