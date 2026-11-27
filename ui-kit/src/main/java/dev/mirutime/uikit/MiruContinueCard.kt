package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The one "continue reading" block: Dashboard and Library render the same
// card. The cover is a slot so this module stays free of app cover/domain
// code; the caller owns retry/reload and the update dot.
// Geometry: the cover is a 1:1.45 portrait sized from MiruSize.ContinueCover
// and scaled with the font, so it stays the tallest element of the row and
// fills the card height instead of floating as a small thumb. A height-derived
// cover (IntrinsicSize.Min + fillMaxHeight) was tried and blows the card up:
// the weighted text column gets ~0 width during the intrinsic pass.
@Composable
fun MiruContinueCard(
    title: String,
    meta: String,
    progressFraction: Float,
    progressLabel: String,
    continueDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    // Same ladder as the dashboard rails: the cover grows with the text so the
    // card does not turn into a plate frame around a tiny cover at 200%.
    val coverScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    MiruCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(MiruSpace.M),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(MiruSize.ContinueCover * coverScale)
                    .aspectRatio(1f / 1.45f),
                content = cover,
            )
            Column(modifier = Modifier.weight(1f)) {
                MiruCardTitleText(
                    title = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CardProgressBar(
                    fraction = progressFraction,
                    height = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MiruSpace.S),
                )
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
            }
            Box(
                modifier = Modifier
                    .compactTouchTarget(MiruSize.ControlCompactLarge)
                    .size(MiruSize.ControlCompactLarge)
                    .clip(CircleShape)
                    .background(extra.accent.copy(alpha = 0.16f))
                    .border(1.dp, extra.accent.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = continueDescription,
                    tint = extra.accent,
                    modifier = Modifier.size(MiruSize.IconSlot),
                )
            }
        }
    }
}
