package dev.mirutime.app.features.library.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.toPreset
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.uikit.MiruBookCard
import dev.mirutime.uikit.MiruContentKind
import dev.mirutime.uikit.MiruMembershipBadge
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.ratingText

// One adapter over the shared MiruBookCard renderer. The public signature is
// kept for every existing caller; compactInfo turns the card into the plugin
// catalog presentation (no type/source/chapters), membership adds the NoveLA
// bookmark. The cover's own retry tile is the only error affordance here.
@Composable
fun LibraryCard(
    book: LibraryBook,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.Mat,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onRetry: (suspend () -> String?)? = null,
    onLongPress: () -> Unit = {},
    badge: String? = null,
    showTypeChip: Boolean = true,
    compactInfo: Boolean = false,
    membership: MiruMembershipBadge? = null,
) {
    val catalog = compactInfo
    val kind = if (showTypeChip && !catalog) book.contentType.toMiruKind() else null
    val read = if (catalog) null else book.readChapters
    val total = if (catalog) null else book.totalChapters
    val effectiveMembership = membership
        ?: if (!badge.isNullOrBlank()) MiruMembershipBadge(sameSource = true, sourceCount = 1) else null
    val membershipDescription = effectiveMembership?.let {
        when {
            it.sourceCount > 1 -> stringResource(
                R.string.lib_membership_count_fmt,
                it.sourceCount,
            )
            it.sameSource -> stringResource(R.string.lib_membership_same)
            else -> stringResource(R.string.lib_membership_other)
        }
    }.orEmpty()
    MiruBookCard(
        preset = style.toPreset(),
        title = book.displayTitle(),
        modifier = modifier,
        source = if (catalog) null else book.sourceLabel,
        rating = ratingText(book.rating),
        readChapters = read,
        totalChapters = total,
        newChapters = if (catalog) 0 else book.newChapters,
        progressDescription = if (read != null && total != null) {
            stringResource(R.string.card_progress_cd_fmt, read, total)
        } else {
            ""
        },
        kind = kind,
        membership = effectiveMembership,
        selected = selected,
        selectionMode = selectionMode,
        hasUpdate = book.hasUpdate,
        onClick = onOpen,
        onLongClick = onLongPress,
        membershipDescription = membershipDescription,
        contentTypeDescription = if (kind != null) {
            stringResource(book.contentType.labelRes())
        } else {
            ""
        },
        updateDescription = stringResource(R.string.lib_cd_updated),
        selectionDescription = stringResource(R.string.lib_cd_selected),
        cover = {
            BookCover(
                title = book.displayTitle(),
                modifier = Modifier.fillMaxSize(),
                coverUrl = book.coverUrl,
                corner = MiruRadius.Cover,
                // Remote art already carries the title; keep the initial
                // only for a genuinely empty cover, never over loaded art.
                showInitial = book.coverUrl.isNullOrBlank(),
                onRetry = onRetry,
            )
        },
    )
}

private fun ContentType.toMiruKind(): MiruContentKind = when (this) {
    ContentType.Novel -> MiruContentKind.Novel
    ContentType.Manga -> MiruContentKind.Manga
    ContentType.Video -> MiruContentKind.Video
}

@StringRes
private fun ContentType.labelRes(): Int = when (this) {
    ContentType.Novel -> R.string.type_novel
    ContentType.Manga -> R.string.type_manga
    ContentType.Video -> R.string.type_video
}

private fun LibraryBook.displayTitle(): String =
    translatedTitle?.takeIf { it.isNotBlank() } ?: title
