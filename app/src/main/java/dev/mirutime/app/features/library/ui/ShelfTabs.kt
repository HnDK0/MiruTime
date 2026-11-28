package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTabPill
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.uikit.miruPlateBorder

// Shelves row: secondary scrollable tabs (M3 pattern) with a segmented-control
// look — flat text tabs, the selected ones on a soft accent pill, never chips.
// "All" is the leading tab and means no shelf filter (empty selection); a tap
// on a shelf switches to it alone (single-select). Long-press
// opens the shelf manager; the manage icon duplicates that entry.
//
// The tabs sit on their OWN plate: one track for the shelf group, the same
// floating plate the app bar and cards use. That is the boundary the eye reads
// between the rows above and below it — over a photo no hairline survives, and
// a group of pills with no container is exactly what made shelves and filters
// look like one strip of chips.
@Composable
fun ShelfTabsRow(
    shelves: List<Shelf>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onManage: () -> Unit,
    onTabLongPress: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MiruRadius.Plate),
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        border = miruPlateBorder(),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MiruSpace.S, end = MiruSpace.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiruTabPill(
                    label = stringResource(R.string.shelf_all),
                    selected = selectedIds.isEmpty(),
                    onClick = onSelectAll,
                )
                shelves.forEach { shelf ->
                    MiruTabPill(
                        label = shelfLabel(shelf),
                        selected = shelf.id in selectedIds,
                        onClick = { onToggle(shelf.id) },
                        onLongClick = { onTabLongPress(shelf) },
                    )
                }
            }
            // Round photo button on the one size grid: 40dp everywhere.
            MiruRoundIconButton(
                icon = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.shelf_cd_manage),
                onClick = onManage,
            )
        }
    }
}
