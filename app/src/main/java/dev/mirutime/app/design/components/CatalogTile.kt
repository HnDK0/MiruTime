package dev.mirutime.app.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.LibraryBadgeState
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruBookCard
import dev.mirutime.uikit.MiruCardPreset
import dev.mirutime.uikit.MiruCardType
import dev.mirutime.uikit.MiruMembershipBadge
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.ratingText

internal fun LibraryBadgeState.toMiruBadge(): MiruMembershipBadge =
    MiruMembershipBadge(sameSource = inSameSource, sourceCount = sourceCount)

// Shared catalogue tile used by global search, the source catalogue and the
// migration flows: the same card language as the library, but the catalogue
// data set (title + rating + membership bookmark). `plain` removes the second
// plate inside a section card.
@Composable
fun CatalogTile(
    book: CatalogBook,
    inLibrary: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    plain: Boolean = false,
    selected: Boolean = false,
    chapterLabel: String? = null,
    caption: String? = null,
    interactive: Boolean = true,
    selectionMode: Boolean = false,
    showInitial: Boolean = true,
    placeholderIcon: ImageVector? = null,
    membership: MiruMembershipBadge? = null,
) {
    val badge = membership
        ?: if (inLibrary) MiruMembershipBadge(sameSource = true, sourceCount = 1) else null
    val membershipDescription = badge?.let {
        when {
            it.sourceCount > 1 -> stringResource(R.string.lib_membership_count_fmt, it.sourceCount)
            it.sameSource -> stringResource(R.string.finder_badge_in_library)
            else -> stringResource(R.string.lib_membership_other)
        }
    }.orEmpty()
    val footer: (@Composable () -> Unit)? = chapterLabel?.let { label ->
        {
            Text(
                text = label,
                maxLines = 1,
                style = MiruCardType.Meta,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    val click: () -> Unit = if (interactive) onClick else ({})
    val longClick: () -> Unit = if (interactive) onLongClick else ({})
    MiruBookCard(
        preset = if (plain) MiruCardPreset.Share else MiruCardPreset.Mat,
        title = book.title,
        modifier = modifier,
        source = caption,
        rating = ratingText(book.rating),
        membership = badge,
        selected = selected,
        selectionMode = selectionMode,
        onClick = click,
        onLongClick = longClick,
        membershipDescription = membershipDescription,
        selectionDescription = stringResource(R.string.lib_cd_selected),
        footer = footer,
        cover = {
            Box(modifier = Modifier.fillMaxSize()) {
                BookCover(
                    title = book.title,
                    modifier = Modifier.fillMaxSize(),
                    coverUrl = book.coverUrl,
                    corner = MiruRadius.Cover,
                    showInitial = showInitial && book.coverUrl.isNullOrBlank(),
                    placeholderIcon = placeholderIcon,
                )
            }
        },
    )
}
