package dev.mirutime.uikit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast

// Bottom-sheet standard: content-driven height capped at 70%
// of the bounded modal window (including IME resizing), title plus a
// line saying what the menu is for, collapsible sections that all scroll,
// one or two bottom buttons (Close and Done) raised above gesture nav.
// Tablet: the sheet surface itself is capped at 600dp wide and centred, so
// callers never add their own width rules.
// The native handle remains outside caller content. Margins and handle count
// toward the cap; navigation insets are owned by the footer INSIDE this budget.
internal fun sheetContentMaxHeight(
    availableHeight: Dp,
    dragHandleHeight: Dp,
    maxHeightFraction: Float,
): Dp {
    val overhead = dragHandleHeight + MiruSpace.M
    return ((availableHeight + overhead) * maxHeightFraction.coerceAtMost(MiruSize.SheetMaxFraction) - overhead)
        .coerceAtLeast(0.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    title: String,
    description: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String? = null,
    onDone: (() -> Unit)? = null,
    doneEnabled: Boolean = true,
    // The caller's write is still running: Done shows a spinner next to its
    // label instead of looking like an unresponsive disabled button.
    doneBusy: Boolean = false,
    // Dismiss wording: "Close" for browsing sheets, "Cancel" where the sheet
    // is a decision (destructive confirms) so declining reads as declining.
    closeLabel: String? = null,
    // Danger confirms (delete flows) paint the filled button error-red.
    // Restrained destructive actions: one red button per sheet max.
    doneDanger: Boolean = false,
    // Content-driven sheets cap at 70%; a caller can ask for less, never more.
    maxHeightFraction: Float = MiruSize.SheetMaxFraction,
    // A list container (LazyColumn) owns its scrolling: the default
    // verticalScroll wrapper would measure it with infinite height.
    // Optional top-right action in the title row (e.g. Reset). Sits on the
    // same baseline as the title with one spacing step from the edge.
    headerAction: (@Composable () -> Unit)? = null,
    contentScrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var dragHandleHeightPx by remember(density) { mutableIntStateOf(with(density) { MiruSize.Touch.roundToPx() }) }
    // Floating-sheet standard: 12dp margins all round so every corner shows
    // the 23px radius (top AND bottom) and the footer always clears gesture
    // nav. Width is capped inside the component: phones keep the full width,
    // tablets get a 600dp sheet centred on the screen.
    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = modifier
            .widthIn(max = MiruSize.ContentMaxWidth)
            .padding(start = MiruSpace.M, end = MiruSpace.M, bottom = MiruSpace.M),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = MiruSize.ContentMaxWidth,
        shape = RoundedCornerShape(SheetCorner),
        containerColor = MaterialTheme.colorScheme.surface,
        // Keep native handle/dismiss/drag behavior; only its measured height is used.
        dragHandle = {
            Box(Modifier.onSizeChanged { dragHandleHeightPx = it.height }) {
                BottomSheetDefaults.DragHandle()
            }
        },
        // Native IME padding still bounds the modal window. Navigation padding
        // moves inside our footer so it cannot sit outside the seventy-percent cap.
        contentWindowInsets = { WindowInsets(0) },
        // User rule: scrims stay semi-transparent (content shows through),
        // pinned explicitly instead of relying on the M3 default.
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentMaxHeight = sheetContentMaxHeight(
                availableHeight = maxHeight,
                dragHandleHeight = with(density) { dragHandleHeightPx.toDp() },
                maxHeightFraction = maxHeightFraction,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .padding(horizontal = MiruSpace.L),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    headerAction?.let {
                        Spacer(modifier = Modifier.width(MiruSpace.S))
                        it()
                    }
                }
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(MiruSpace.Xs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.L))
                if (contentScrollable) {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .fadeScrollEdges(scroll, MaterialTheme.colorScheme.surface)
                            .verticalScroll(scroll),
                    ) {
                        content()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        content()
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MiruSpace.L)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = MiruSpace.L),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    // Visible secondary Close/Cancel; operation busy and enabled stay independent.
                    MiruButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        style = MiruButtonStyle.Secondary,
                    ) {
                        Text(text = closeLabel ?: stringResource(R.string.lib_close))
                    }
                    if (doneLabel != null && onDone != null) {
                        MiruButton(
                            onClick = onDone,
                            enabled = doneEnabled,
                            busy = doneBusy,
                            modifier = Modifier.weight(1f),
                            style = if (doneDanger) MiruButtonStyle.Destructive else MiruButtonStyle.Primary,
                        ) {
                            Text(text = doneLabel)
                        }
                    }
                }
            }
        }
    }
}

// One collapsible section inside a sheet (expanded by default or not).
@Composable
fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    // Plugin-declared labels already carry their own casing; uppercasing a
    // sentence-length label turns it into a shouting wall, so those sections
    // keep the text as authored while app-owned headers stay small caps.
    preserveCase: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = MiruSize.Touch)
                .padding(vertical = MiruSpace.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiruPickerHeader(
                text = title,
                preserveCase = preserveCase,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = LocalMiruExtraColors.current.muted,
                modifier = Modifier.size(MiruSize.Icon),
            )
        }
        if (expanded) {
            content()
        }
    }
}

// Content scrolls under the sheet title and into the footer; short fades on
// both edges hide the hard cut of a row crossing the viewport (a sliced line
// of glyphs reads as broken layout, not as "more content"). Each fade only
// grows while there is content left in that direction, so scrolled to an edge
// nothing stays dimmed. The fade paints the sheet surface colour over the
// content (vertical alpha) instead of erasing the layer, so a sheet stacked
// above another one never shows what is underneath it through the gradient.
private fun Modifier.fadeScrollEdges(scroll: ScrollState, surface: Color): Modifier = drawWithContent {
    drawContent()
    val fadeHeight = MiruSpace.Xl.toPx()
    if (scroll.value > 0) {
        val visibleFade = (fadeHeight * (scroll.value / fadeHeight)).coerceAtMost(fadeHeight)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(surface, surface.copy(alpha = 0f)),
                startY = 0f,
                endY = visibleFade,
            ),
            size = Size(size.width, visibleFade),
        )
    }
    val remaining = scroll.maxValue - scroll.value
    if (remaining > 0) {
        val visibleFade = (fadeHeight * (remaining / fadeHeight)).coerceAtMost(fadeHeight)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(surface.copy(alpha = 0f), surface),
                startY = size.height - visibleFade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - visibleFade),
            size = Size(size.width, visibleFade),
        )
    }
}
