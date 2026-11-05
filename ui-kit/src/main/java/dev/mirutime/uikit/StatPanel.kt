package dev.mirutime.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class StatCell(
    val value: String,
    val label: String,
    val icon: ImageVector? = null,
    // Relative width in the horizontal strip (mock: 3 / 5 / 4).
    val weight: Float = 1f,
    // Optional progress bar under the value (chapter progress).
    val progress: Float? = null,
    // Optional second caption line (e.g. "7 downloaded").
    val secondary: String? = null,
)

// Stat strip: values titleMedium (16sp semibold), labels bodySmall muted,
// 12dp vertical padding, hairline dividers. Cells share the strip by weight
// and never wrap the value (ellipsis) so the height never jumps. At large
// system text (or a narrow width) the strip stacks instead of squeezing.
@Composable
fun StatPanel(
    cells: List<StatCell>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = LocalDensity.current.fontScale > 1.3f || maxWidth < 320.dp
            if (stacked) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MiruSpace.M),
                ) {
                    cells.forEachIndexed { index, cell ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = MiruSpace.L),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        StatCellContent(
                            cell = cell,
                            centered = false,
                            modifier = Modifier.padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = MiruSpace.M),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    cells.forEachIndexed { index, cell ->
                        if (index > 0) {
                            VerticalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        StatCellContent(
                            cell = cell,
                            centered = true,
                            modifier = Modifier
                                .weight(cell.weight)
                                .padding(horizontal = MiruSpace.Xs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCellContent(
    cell: StatCell,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    val alignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        ) {
            cell.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LocalMiruExtraColors.current.accent,
                    modifier = Modifier.size(MiruSize.IconSmall),
                )
            }
            Text(
                text = cell.value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        cell.progress?.let { fraction ->
            CardProgressBar(
                fraction = fraction,
                height = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.Xs),
            )
        }
        Text(
            text = cell.label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        cell.secondary?.let { secondary ->
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = LocalMiruExtraColors.current.muted,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
