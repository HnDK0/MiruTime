package dev.mirutime.app.design.components

import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface

// === Dashboard book tile ===
// ONE vertical card for every horizontal rail on the dashboard (Continue,
// History). Fixed geometry — 140dp wide, 3:4 cover, 2-line title, one
// "primary" line and one "secondary" line — so every tile in a rail has the
// same height and the same slots, whatever the book.
//
//   cover (+ progress bar, + NEW badge, + small round play button)
//   title                      (2 lines, always reserves both)
//   primary   [accent, bold]   trailing [muted]     e.g. "Ch. 123"      "68%"
//   secondary [muted]                               e.g. "Asura Scans"
//
// The play button is a 32dp accent circle sitting on the cover corner (hit
// box 40dp, same shape). It is optional; the whole tile is always one tap
// target too.
val BookTileWidth = 140.dp
private val TilePad = 8.dp

@Composable
fun BookTile(
    title: String,
    primary: String,
    secondary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryTrailing: String? = null,
    progress: Float? = null,
    badge: String? = null,
    onPlay: (() -> Unit)? = null,
    playIcon: ImageVector = Icons.Rounded.PlayArrow,
    playDescription: String? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = modifier
            .width(BookTileWidth)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column {
            // NoveLA cover: 2dp gutter, 8dp radius, 1 / 1.45 frame.
            Box {
                BookCover(
                    title = title,
                    badge = badge,
                    progress = progress,
                    corner = MiruRadius.Cover,
                    modifier = Modifier
                        .padding(CoverInset)
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.45f),
                )
                if (onPlay != null) {
                    PlayDisc(
                        icon = playIcon,
                        description = playDescription,
                        onClick = onPlay,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 8.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(TilePad)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = primary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = extra.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    primaryTrailing?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.muted,
                            maxLines = 1,
                            modifier = Modifier.padding(start = MiruSpace.S),
                        )
                    }
                }
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp, bottom = 2.dp),
                )
            }
        }
    }
}

// Small "continue" button: 32dp circular disc inside a 40dp hit box. Shared
// with the library cards (dashboard history play button).
@Composable
internal fun PlayDisc(
    icon: ImageVector,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = extra.accent,
            contentColor = extra.onAccent,
            shadowElevation = 4.dp,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = extra.onAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

val ReplayIcon: ImageVector get() = Icons.Rounded.Replay

// Last cell of a rail: same plate, same corner, an arrow and a label. Replaces
// the old full-width "Show all" button under the rail.
@Composable
fun SeeAllTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = modifier
            .width(104.dp)
            .heightIn(min = 132.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = extra.accent,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = extra.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
