package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role

data class ChipOption(val id: String, val label: String)

// Chip id is the LazyRow key; caller-supplied duplicates must never reach it.
internal fun dedupeChipOptions(options: List<ChipOption>): List<ChipOption> =
    options.distinctBy { it.id }

// The one chip rail of the app: a single scrolling line, never wrapping, one
// gap. Action chips (Change source / Options) and toggle chips (ChipRow) go
// through it so no screen re-invents a wrapping FlowRow.
@Composable
fun ChipRail(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        content = content,
    )
}

// Horizontal-scroll chip row (the pattern for every chip strip except the
// genuinely numerous genre/tag groups, which get in-section search later).
// Single line, never wraps; an optional trailing slot holds row-end chips
// (shelf create/trash).
@Composable
fun ChipRow(
    options: List<ChipOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    trailing: (@Composable () -> Unit)? = null,
) {
    ChipRail(modifier = modifier, contentPadding = contentPadding) {
        items(dedupeChipOptions(options), key = { it.id }) { option ->
            MiruFilterChip(
                selected = option.id in selectedIds,
                onClick = { onToggle(option.id) },
                label = option.label,
            )
        }
        if (trailing != null) {
            item(key = "__trailing__") { trailing() }
        }
    }
}

// One chip language AND one size grid for the whole app: compact 28dp chip
// with an 8dp radius (a 12dp radius on 28dp reads as an oval), 12sp label,
// 12dp inner side padding, 48dp touch box — identical in every theme.
// Unselected = the plate + onSurface ink + outline hairline; selected = solid
// accent.
@Composable
fun MiruFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    // Same pair of looks in every theme: unselected = the plate + onSurface
    // ink + outline hairline; selected = solid accent with an ink picked by
    // contrastInkFor. Both are plate/accent verified by the theme builder.
    val backing: Color
    val ink: Color
    val border: Color
    if (!enabled) {
        backing = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        border = Color.Transparent
    } else if (selected) {
        backing = extra.accent
        ink = contrastInkFor(extra.accent, false, listOf(extra.onAccent))
        border = Color.Transparent
    } else {
        backing = miruCardSurface()
        ink = MaterialTheme.colorScheme.onSurface
        border = MaterialTheme.colorScheme.outline
    }
    val shape = RoundedCornerShape(MiruRadius.Chip)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MiruSize.Touch) {
        Box(
            modifier = modifier
                .clip(shape)
                .semantics { this.selected = selected }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = MiruSize.Chip)
                    .clip(shape)
                    .background(backing)
                    .border(MiruSize.Stroke, border, shape)
                    .padding(horizontal = MiruSpace.M),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = ink,
                            modifier = Modifier.size(MiruSize.IconSmall),
                        )
                        Spacer(modifier = Modifier.width(MiruSpace.Xs))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
