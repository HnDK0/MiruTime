package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.uikit.LocalMiruOnImage
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTabPill

// Shelves row: secondary scrollable tabs (M3 pattern) with a segmented-control
// look — flat text tabs, the selected one on a soft accent pill, never chips.
// Tap selects, long-press opens the shelf manager; the manage icon duplicates
// that entry. No trailing "+" and no trash in the row (03-ui.md, 08-library.md).
@Composable
fun ShelfTabsRow(
    shelves: List<Shelf>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onTabLongPress: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onImage = LocalMiruOnImage.current
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
                    MiruTabPill(
                        label = shelfLabel(shelf),
                        selected = shelf.id == selectedId,
                        onClick = { onSelect(shelf.id) },
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
