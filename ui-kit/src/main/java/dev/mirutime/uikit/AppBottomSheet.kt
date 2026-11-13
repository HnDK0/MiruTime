package dev.mirutime.uikit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

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
    // Compact action panels (source/login, download) drop the drag handle and
    // tighten the header. Forms keep the handle and their opaque fields.
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var dragHandleHeightPx by remember(density, compact) {
        mutableIntStateOf(if (compact) 0 else with(density) { MiruSize.SheetHandleBox.roundToPx() })
    }
    // Floating-sheet standard: 12dp margins all round so every corner shows
    // the 23px radius (top AND bottom) and the footer always clears gesture
    // nav. Width is capped inside the component: phones keep the full width,
    // tablets get a 600dp sheet centred on the screen.
    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = modifier
            .widthIn(max = MiruSize.ContentMaxWidth)
            .padding(start = MiruSpace.S, end = MiruSpace.S, bottom = MiruSpace.S),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = MiruSize.ContentMaxWidth,
        shape = RoundedCornerShape(MiruRadius.Sheet),
        containerColor = miruCardSurface(),
        // miruCardSurface() is not a scheme role on the photo theme (alpha
        // < 1), so contentColorFor() would return Unspecified and every
        // colourless Text in the sheet would fall back to black. Pin the
        // plate ink explicitly: one owner for every sheet.
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Compact handle: the sheet still drags natively, but the header spends
        // 24dp instead of the 48dp native handle. Its measured height feeds the
        // seventy-percent budget like before. Compact panels have no handle.
        dragHandle = if (compact) {
            null
        } else {
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MiruSize.SheetHandleBox)
                        .onSizeChanged { dragHandleHeightPx = it.height },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(MiruSize.SheetHandleWidth)
                            .height(MiruSize.SheetHandleHeight)
                            .clip(RoundedCornerShape(MiruSize.SheetHandleHeight / 2f))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
            }
        },
        // IME inset is applied inside the dialog content below; navigation
        // padding moves inside our footer so it cannot sit outside the cap.
        contentWindowInsets = { WindowInsets(0) },
        // User rule: scrims stay semi-transparent (content shows through),
        // pinned explicitly instead of relying on the M3 default.
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // Insets must be applied inside the dialog content; on the
                // ModalBottomSheet window the IME inset is not delivered.
                .imePadding(),
        ) {
            val contentMaxHeight = sheetContentMaxHeight(
                availableHeight = maxHeight,
                dragHandleHeight = with(density) { dragHandleHeightPx.toDp() },
                maxHeightFraction = maxHeightFraction,
            )
            val hPad = if (compact) MiruSpace.M else MiruSpace.L
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) MiruSpace.M else 0.dp)
                        .padding(horizontal = hPad),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    headerAction?.let {
                        Spacer(modifier = Modifier.width(MiruSpace.S))
                        it()
                    }
                }
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = hPad),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.S))
                if (contentScrollable) {
                    val scroll = rememberScrollState()
                    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    // The thumb lives in the sheet's side gutter, outside the
                    // content padding, so it never paints over rows or text and
                    // never competes with the fade at the content edges.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = hPad)
                                .fadeScrollEdges(scroll, miruCardSurface())
                                .verticalScroll(scroll),
                        ) {
                            content()
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .sheetScrollIndicator(
                                    scroll = scroll,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                    isRtl = isRtl,
                                ),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = hPad),
                    ) {
                        content()
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPad)
                        .padding(top = MiruSpace.S)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = MiruSpace.S),
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

// One collapsible section inside a sheet. Collapsed by default: a sheet opens
// as a short index of sections, and only the section the user picks grows.
// Opening also scrolls that section to the top of the sheet, so the user never
// has to hunt for it after tapping.
@Composable
fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    // Short current value shown while collapsed (e.g. "1.25×"), so a closed
    // section still answers "what is it set to?".
    summary: String? = null,
    // Plugin-declared labels already carry their own casing; uppercasing a
    // sentence-length label turns it into a shouting wall, so those sections
    // keep the text as authored while app-owned headers stay small caps.
    preserveCase: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded
    val requester = remember { BringIntoViewRequester() }
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(180),
        label = "sheet-section-chevron",
    )
    // Let the expanding body be laid out before scrolling to the section.
    LaunchedEffect(isExpanded) {
        if (!isExpanded) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        try {
            requester.bringIntoView()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // The section left composition mid-animation; nothing to scroll.
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val next = !isExpanded
                    if (onExpandedChange != null) {
                        onExpandedChange(next)
                    } else {
                        internalExpanded = next
                    }
                }
                .heightIn(min = MiruSize.Touch)
                .padding(vertical = MiruSpace.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiruPickerHeader(
                text = title,
                preserveCase = preserveCase,
                modifier = Modifier.weight(1f),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = MiruSpace.S),
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = LocalMiruExtraColors.current.muted,
                modifier = Modifier
                    .padding(start = MiruSpace.Xs)
                    .size(MiruSize.Icon)
                    .rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

// A thin thumb in the sheet's side gutter tells the user there is more below:
// a sheet whose content ends mid-row looks finished, so the affordance must be
// visible without scrolling first. It tracks [scroll] directly in the draw
// phase, so it never recomposes the sheet content; nothing is drawn when the
// content fits.
private fun Modifier.sheetScrollIndicator(
    scroll: ScrollState,
    color: Color,
    isRtl: Boolean,
): Modifier =
    drawWithContent {
        drawContent()
        if (scroll.maxValue <= 0) return@drawWithContent
        val width = MiruSpace.Xs.toPx()
        val track = size.height
        val content = track + scroll.maxValue
        val thumb = (track * (track / content))
            .coerceIn(MiruSpace.Xl.toPx(), track)
        val travel = track - thumb
        val top = travel * (scroll.value.toFloat() / scroll.maxValue)
        drawRoundRect(
            color = color,
            topLeft = Offset(
                if (isRtl) MiruSpace.Xxs.toPx() else size.width - width - MiruSpace.Xxs.toPx(),
                top,
            ),
            size = Size(width, thumb),
            cornerRadius = CornerRadius(width / 2f),
        )
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
