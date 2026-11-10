package dev.mirutime.uikit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// === Shape scale ===
// Sharp geometry: cards are 8dp, controls 6dp, chips 8dp, covers 6dp.
// Nested radius = outer radius − padding where practical; a card (8) with an
// 8dp padding holds a cover (6), which reads as one concentric family.
object MiruRadius {
    val Badge = 6.dp      // tiny tags on covers
    val Split = 4.dp      // split-progress and small plates
    val Cover = 6.dp      // book covers, inner thumbnails
    val Chip = 8.dp       // filter chips
    val Control = 6.dp    // buttons, outlined fields and compact controls
    val Inner = 8.dp      // tiles nested inside a card
    val Card = 8.dp       // cards, plates, panels
    val Large = 8.dp      // medium containers and overlay surfaces
    val Plate = 8.dp      // floating chrome (header, tab bar)
    val Sheet = 8.dp      // bottom sheets / compact panels
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
// Rule: visual size and touch size are separate. Comfortable actions keep a
// 48dp hit area; dense surfaces use the compact ladder below. The visible
// shape may be smaller. Text: body 12sp, labels 11sp, meta 10sp.
object MiruSize {
    val Touch = 48.dp
    val Control = 40.dp      // round icon buttons and field rows (visual), 6dp radius
    val Chip = 24.dp         // filter chips (visual; at least 48dp touch)
    val SplitProgress = 56.dp // split-progress pill (chapters read / total)
    val SplitProgressHeight = 16.dp
    val Button = 40.dp       // secondary buttons / plate pills
    val ButtonCompact = 32.dp // compact plates (chapter toolbar, selection strip)
    val ButtonPrimary = 48.dp // the one loud action of a screen
    val Row = 48.dp          // single-line list rows (tools, settings)
    val RowDense = 40.dp     // dense list rows (chapters) with compact meta
    val RowCompact = 32.dp   // compact list rows (dense chapters, metadata)
    val ControlCompact = 28.dp // smallest compact control face
    val ControlCompactLarge = 32.dp // compact control face with a text label
    val ChipCompact = Chip   // compact chip face in a dense rail
    val IconSlot = 20.dp     // non-interactive glyph slot next to a row title
    val NarrowContent = 340.dp // stack paired actions below this content width
    val StatStripHeight = 56.dp // compact stat row; heightIn lets it grow at large text
    val ScreenPad = MiruSpace.L
    val Gap = MiruSpace.L    // between stacked cards
    val SectionGap = MiruSpace.Xl
    val CardPad = MiruSpace.M
    // Book detail cover (2:3). Bigger than a library thumb, the page's hero.
    val DetailCoverWidth = 104.dp
    val DetailCoverHeight = 152.dp
    // Catalogue/global-search tile width (CatalogTile in a LazyRow).
    val Tile = 140.dp
    val IconSmall = 14.dp
    val IconPlate = 16.dp
    val IconCompact = 18.dp
    val Icon = 22.dp
    val Indicator = 16.dp
    val Stroke = 1.dp
    val StrokeFocus = 2.dp
    val ContentMaxWidth = 600.dp
    const val SheetMaxFraction = 0.7f
    // Compact sheet handle: the visual bar sits in a short box so the header
    // keeps its height for content instead of a 48dp native handle.
    val SheetHandleBox = 24.dp
    val SheetHandleWidth = 32.dp
    val SheetHandleHeight = 4.dp
    // Speed/pitch rows: the label and the value keep the slider on the same
    // axis across rows.
    val SliderLabelWidth = 56.dp
    val SliderValueWidth = 56.dp
}

// Touch target for compact controls: the interactive surface plus 8dp, clamped
// to [32dp, 48dp]. Surfaces larger than 48dp keep their own size.
// Full-row targets use the row height directly; sliders use the thumb.
fun compactTouchTarget(surface: Dp): Dp =
    maxOf(surface, (surface + 8.dp).coerceIn(32.dp, 48.dp))

val MiruShapes = Shapes(
    extraSmall = RoundedCornerShape(MiruRadius.Cover),
    small = RoundedCornerShape(MiruRadius.Control),
    medium = RoundedCornerShape(MiruRadius.Card),
    large = RoundedCornerShape(MiruRadius.Plate),
    extraLarge = RoundedCornerShape(topStart = MiruRadius.Sheet, topEnd = MiruRadius.Sheet),
)
