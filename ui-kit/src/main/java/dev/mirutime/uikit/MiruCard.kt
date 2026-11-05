package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// THE plate. Every block of text on a screen sits on one of these. On the
// photo it is the 92% tinted plate (all inks are verified against it); on
// classic themes it is the plain opaque surface. Never put bare text on the
// photo — that is how contrast got lost before.
@Composable
fun MiruCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(MiruSize.CardPad),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

// === Horizontal list-card composite ===
// THE one owner of the library's list variant "C" blueprint (LibraryCard.kt
// VariantC delegates its shell here; dashboard continue/all-updates cards use
// it directly): 12dp-radius card, 12dp padding, 96dp-wide 1:1.45 thumb,
// head row = badge plate + bare gold "★ x.x", 15sp bold title (3 lines),
// 12sp source line, bottom-pinned thin gold bar (4dp) + "X / Y гл." counts
// row. Optional full-width footer (the dashboard continue button) pinned
// under the content, inside the same plate.
@Composable
fun MiruListCard(
    title: String,
    thumb: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    badge: String? = null,
    rating: Float? = null,
    source: String? = null,
    progress: Float? = null,
    countsLine: String? = null,
    moreLine: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Card)
    val shell = modifier
        .miruCardShadow(shape)
        .clip(shape)
        .background(miruCardSurface())
    val root = if (onClick != null) shell.clickable(onClick = onClick) else shell
    Column(modifier = root) {
        // IntrinsicSize.Min pins the row height to the thumb (96×1.45) while
        // bottom-pinning the bar/counts group; the row still grows when the
        // text content (e.g. a 3-line title) exceeds the thumb height.
        // The thumb sits on the plate's leading edge — only the text carries
        // the 12dp inset, so the cover is not framed.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(1f / 1.45f)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)),
                content = thumb,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(MiruSpace.M),
            ) {
                if (badge != null || rating != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        badge?.let { BadgePlate(label = it) }
                        rating?.takeIf { it > 0f && it <= 5f }?.let { value ->
                            Text(
                                text = stringResource(
                                    R.string.card_star_fmt,
                                    ListRating(value),
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = extra.accent,
                                modifier = Modifier.padding(start = MiruSpace.S),
                            )
                        }
                    }
                }
                Text(
                    text = title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
                source?.let { text ->
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MiruSpace.Xxs),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                progress?.let { fraction ->
                    ListBar(fraction = fraction)
                }
                if (countsLine != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MiruSpace.S),
                    ) {
                        Text(
                            text = countsLine,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        moreLine?.let { text ->
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = MiruSpace.S),
                            )
                        }
                    }
                }
            }
        }
        footer?.let { slot ->
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MiruSpace.M, end = MiruSpace.M, bottom = MiruSpace.M),
            ) { slot() }
        }
    }
}

// "4.5" / "4" one-decimal rating per the library blueprint (zeros trimmed).
private fun ListRating(rating: Float): String =
    String.format(Locale.US, "%.1f", rating).trimEnd('0').trimEnd('.')

// C badge plate (LibraryCard TypeChip twin): 4dp-radius accent plate.
@Composable
private fun BadgePlate(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = LocalMiruExtraColors.current.accent,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.onAccent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// Same style as the time dashboard's bars: ink@16% track, accent fill, both
// fully clipped so the fill has rounded ends. Height is per-context.
@Composable
private fun ListBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(LocalMiruExtraColors.current.accent),
        )
    }
}
