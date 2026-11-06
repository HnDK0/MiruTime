package dev.mirutime.uikit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// One shelf tab: a 32dp pill with a 36dp touch box. Over a photo theme each
// pill stands on its own (the active one in the dynamic accent with a
// contrast-picked ink, inactive ones as adaptive ghost pills); classic themes
// keep the soft accent wash. Long-press opens the shelf manager.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiruTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    val onImage = LocalMiruOnImage.current
    val accent = LocalMiruExtraColors.current
    val zone = accent.photoTop
    // 12% of a light accent over a dark surface reads as an unselected tab;
    // dark themes get a denser pill so the active shelf keeps its accent.
    val selectedPillAlpha =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.24f else 0.12f
    val ink = if (selected) {
        if (onImage) accent.accent else MaterialTheme.colorScheme.primary
    } else if (onImage) {
        zone.pillInk
    } else {
        accent.muted
    }
    Box(
        modifier = modifier
            .heightIn(min = MiruSize.Touch)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(MiruRadius.Control))
                .background(
                    if (selected) {
                        if (onImage) {
                            accent.accent.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = selectedPillAlpha)
                        }
                    } else if (onImage) {
                        zone.pillBackdrop
                    } else {
                        Color.Transparent
                    },
                )
                .then(
                    if (selected && onImage) {
                        Modifier.border(1.dp, accent.accent.copy(alpha = 0.35f), RoundedCornerShape(MiruRadius.Control))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = MiruSpace.M),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = iconContentDescription,
                        tint = ink,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = ink,
                )
            }
        }
    }
}
