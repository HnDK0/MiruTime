package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class MiruContentKind { Novel, Manga, Video }

// NoveLA membership: same source = filled bookmark, another plugin = muted,
// a number replaces the glyph when the title exists in more than one source.
data class MiruMembershipBadge(
    val sameSource: Boolean,
    val sourceCount: Int,
)

internal fun memberBadge(sameSource: Boolean, sourceCount: Int): MiruMembershipBadge =
    MiruMembershipBadge(sameSource = sameSource, sourceCount = sourceCount.coerceAtLeast(1))

// A 0..5 score, or nothing; hidden when absent (never a fake 0).
fun ratingText(value: Float): String? {
    if (value <= 0f || value > 5f) return null
    return String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
}

@Composable
fun MiruContentTypeIcon(
    kind: MiruContentKind,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (kind) {
        MiruContentKind.Novel -> Icons.Filled.MenuBook
        MiruContentKind.Manga -> Icons.Filled.Image
        MiruContentKind.Video -> Icons.Filled.Movie
    }
    val extra = LocalMiruExtraColors.current
    Surface(
        modifier = modifier.size(20.dp),
        shape = RoundedCornerShape(MiruRadius.Split),
        color = extra.accent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = extra.onAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
fun MiruRatingLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(MiruRadius.Split),
        color = extra.accent,
    ) {
        Text(
            text = "★ $text",
            maxLines = 1,
            style = MiruCardType.Rating,
            color = extra.onAccent,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun MiruMembershipBookmark(
    badge: MiruMembershipBadge,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val container = if (badge.sameSource) {
        extra.accent
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    }
    val content = if (badge.sameSource) extra.onAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(width = 18.dp, height = 30.dp)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .drawBehind {
                val notch = size.height * 0.22f
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2f, size.height - notch)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = container)
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier.padding(top = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (badge.sourceCount > 1) {
                Text(
                    text = if (badge.sourceCount > 99) "99+" else badge.sourceCount.toString(),
                    maxLines = 1,
                    style = MiruCardType.Num.copy(fontWeight = FontWeight.Bold),
                    color = content,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
fun MiruUpdateDot(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(MaterialTheme.colorScheme.secondary, CircleShape)
            .semantics { this.contentDescription = contentDescription },
    )
}

@Composable
fun MiruSelectionCheck(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = modifier
            .size(18.dp)
            .background(extra.accent, CircleShape)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = extra.onAccent,
            modifier = Modifier.size(13.dp),
        )
    }
}

// Unselected state in migration/selection mode: a hollow ring, never a
// second filled badge.
@Composable
fun MiruSelectionEmpty(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = modifier
            .size(18.dp)
            .border(1.5.dp, extra.accent, CircleShape)
            .semantics { this.contentDescription = contentDescription },
    )
}
