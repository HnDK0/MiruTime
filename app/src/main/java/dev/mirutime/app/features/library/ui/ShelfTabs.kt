package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.LocalMiruOnImage
import dev.mirutime.uikit.contrastInkFor
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.uikit.MiruRoundIconButton

// Shelves row: secondary scrollable tabs (M3 pattern) with a segmented-control
// look — flat text tabs, the selected one on a soft accent pill, never chips.
// Tap selects, long-press opens the shelf manager; the manage icon duplicates
// that entry. No trailing "+" and no trash in the row (03-ui.md, 08-library.md).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfTabsRow(
    shelves: List<Shelf>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onTabLongPress: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Over a custom image there is no shared box: each tab is its own pill
    // straight on the photo — the active one dense in the dynamic accent
    // (ink picked by the contrast guard, never vanishing), inactive ones
    // adaptive ghost pills (light backing + dark ink on bright strips, dark
    // + white on dark — roles measured at decode). The hairline divider
    // hides on the photo. Classic themes keep the segmented-control look.
    val onImage = LocalMiruOnImage.current
    val accent = LocalMiruExtraColors.current
    val zone = accent.photoTop
    // 12% of a light accent over a dark surface reads as an unselected tab;
    // dark themes get a denser pill so the active shelf keeps its accent.
    val selectedPillAlpha =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.24f else 0.12f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shelves.forEach { shelf ->
                    val selected = shelf.id == selectedId
                    // ≥36dp touch box; the visual pill is EXACTLY 32dp tall
                    // (fixed height + centered content — padding scaling can
                    // never collapse it again).
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .combinedClickable(
                                onClick = { onSelect(shelf.id) },
                                onLongClick = { onTabLongPress(shelf) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(MiruRadius.Chip))
                                .background(
                                    if (selected) {
                                        if (onImage) {
                                            accent.accent
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = selectedPillAlpha)
                                        }
                                    } else if (onImage) {
                                        zone.pillBackdrop
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .padding(horizontal = MiruSpace.L),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = shelf.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (selected) {
                                    if (onImage) {
                                        contrastInkFor(accent.accent, false, listOf(accent.onAccent))
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                } else if (onImage) {
                                    zone.pillInk
                                } else {
                                    accent.muted
                                },
                            )
                        }
                    }
                }
            }
            // Round photo button on the one size grid: 40dp everywhere.
            MiruRoundIconButton(
                icon = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.shelf_cd_manage),
                onClick = onManage,
            )
        }
        if (!onImage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
