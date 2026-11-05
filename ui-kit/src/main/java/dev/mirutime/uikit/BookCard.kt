package dev.mirutime.uikit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 6 grid presets + the 96 dp list. The preset changes
// placement/surface, never the data ownership. Catalog callers pass source,
// kind, read/total as null; the card then ends at the title.
enum class MiruCardPreset { Mat, Above, Share, OnCover, OnCoverAbove, ProgressAbove, List }

internal data class CardLayoutSpec(
    val titleOnCover: Boolean,
    val statusAbove: Boolean,
    val progressFullWidth: Boolean,
    val surface: Boolean,
)

internal fun layoutSpec(preset: MiruCardPreset): CardLayoutSpec = when (preset) {
    MiruCardPreset.Mat -> CardLayoutSpec(false, false, false, true)
    MiruCardPreset.Above -> CardLayoutSpec(false, true, false, true)
    // Internal flat preset for a tile inside a section card; not selectable.
    MiruCardPreset.Share -> CardLayoutSpec(false, false, false, false)
    MiruCardPreset.OnCover -> CardLayoutSpec(true, false, false, true)
    MiruCardPreset.OnCoverAbove -> CardLayoutSpec(true, true, false, true)
    MiruCardPreset.ProgressAbove -> CardLayoutSpec(false, false, true, true)
    MiruCardPreset.List -> CardLayoutSpec(false, false, false, true)
}

private const val CoverAspect = 1f / 1.45f
private val CardShape = RoundedCornerShape(MiruRadius.Card)
private val CoverShape = RoundedCornerShape(MiruRadius.Cover)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiruBookCard(
    preset: MiruCardPreset,
    title: String,
    modifier: Modifier = Modifier,
    source: String? = null,
    rating: String? = null,
    readChapters: Int? = null,
    totalChapters: Int? = null,
    newChapters: Int = 0,
    progressDescription: String = "",
    kind: MiruContentKind? = null,
    membership: MiruMembershipBadge? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    hasUpdate: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    membershipDescription: String = "",
    contentTypeDescription: String = "",
    updateDescription: String = "",
    selectionDescription: String = "",
    footer: (@Composable () -> Unit)? = null,
    cover: @Composable BoxScope.() -> Unit,
) {
    val spec = layoutSpec(preset)
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceIn(1f, 2f)
    val splitWidth = SplitProgressWidth * fontScale
    val titleSlot = with(density) { (MiruCardType.Title.lineHeight * 2).toDp() } + 4.dp
    if (preset == MiruCardPreset.List) {
        ListCard(
            title = title,
            source = source,
            rating = rating,
            readChapters = readChapters,
            totalChapters = totalChapters,
            newChapters = newChapters,
            progressDescription = progressDescription,
            kind = kind,
            membership = membership,
            selected = selected,
            selectionMode = selectionMode,
            hasUpdate = hasUpdate,
            onClick = onClick,
            onLongClick = onLongClick,
            membershipDescription = membershipDescription,
            contentTypeDescription = contentTypeDescription,
            updateDescription = updateDescription,
            selectionDescription = selectionDescription,
            splitWidth = splitWidth,
            titleSlot = titleSlot,
            cover = cover,
            modifier = modifier,
        )
        return
    }

    val selectedModifier = if (selected) {
        Modifier.border(2.dp, LocalMiruExtraColors.current.accent, CardShape)
    } else {
        Modifier
    }
    val stateSemantics = if (selected || selectionMode) {
        Modifier.semantics(mergeDescendants = true) { this.selected = selected }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val surfaceModifier = if (spec.surface) {
        Modifier
            .miruCardShadow(CardShape)
            .clip(CardShape)
            .background(miruCardSurface())
    } else {
        Modifier.clip(CardShape)
    }
    Column(
        modifier = modifier
            .then(surfaceModifier)
            .then(selectedModifier)
            .then(stateSemantics)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (spec.progressFullWidth && (readChapters != null || totalChapters != null)) {
            MiruSplitProgress(
                read = readChapters,
                total = totalChapters,
                contentDescription = progressDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (spec.statusAbove && !source.isNullOrBlank()) {
            StatusRow(
                source = source,
                read = readChapters,
                total = totalChapters,
                newChapters = newChapters,
                progressDescription = progressDescription,
                splitWidth = splitWidth,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        CoverBox(
            title = title,
            rating = rating,
            kind = kind,
            membership = membership,
            selected = selected,
            selectionMode = selectionMode,
            hasUpdate = hasUpdate,
            titleOnCover = spec.titleOnCover,
            membershipDescription = membershipDescription,
            contentTypeDescription = contentTypeDescription,
            updateDescription = updateDescription,
            selectionDescription = selectionDescription,
            // Progress-above cards carry the source on the cover's bottom-left
            // instead of a line under the title.
            bottomSource = if (spec.progressFullWidth) source else null,
            bottomNewChapters = if (spec.progressFullWidth) newChapters else 0,
            cover = cover,
        )
        if (spec.titleOnCover) {
            // The cover carries the only title; nothing below it.
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleSlot)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MiruCardType.Title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (!spec.progressFullWidth && !spec.statusAbove && !source.isNullOrBlank()) {
            StatusRow(
                source = source,
                read = readChapters,
                total = totalChapters,
                newChapters = newChapters,
                progressDescription = progressDescription,
                splitWidth = splitWidth,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        footer?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListCard(
    title: String,
    source: String?,
    rating: String?,
    readChapters: Int?,
    totalChapters: Int?,
    newChapters: Int,
    progressDescription: String,
    kind: MiruContentKind?,
    membership: MiruMembershipBadge?,
    selected: Boolean,
    selectionMode: Boolean,
    hasUpdate: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    membershipDescription: String,
    contentTypeDescription: String,
    updateDescription: String,
    selectionDescription: String,
    splitWidth: Dp,
    titleSlot: Dp,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier,
) {
    val selectedModifier = if (selected) {
        Modifier.border(2.dp, LocalMiruExtraColors.current.accent, CardShape)
    } else {
        Modifier
    }
    val stateSemantics = if (selected || selectionMode) {
        Modifier.semantics(mergeDescendants = true) { this.selected = selected }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier = modifier
            .miruCardShadow(CardShape)
            .clip(CardShape)
            .background(miruCardSurface())
            .then(selectedModifier)
            .then(stateSemantics)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(CoverAspect)
                .clip(CoverShape),
        ) {
            cover()
            rating?.let {
                MiruRatingLabel(
                    text = it,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            membership?.let {
                MiruMembershipBookmark(
                    badge = it,
                    contentDescription = membershipDescription,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (-2).dp),
                )
            }
            if (selected) {
                MiruSelectionCheck(
                    contentDescription = selectionDescription,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                )
            } else if (selectionMode) {
                MiruSelectionEmpty(
                    contentDescription = selectionDescription,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleSlot),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MiruCardType.Title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!source.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (kind != null) {
                        MiruContentTypeIcon(
                            kind = kind,
                            contentDescription = contentTypeDescription,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    MiruSourceLabel(
                        text = source,
                        newChapters = newChapters,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (readChapters != null || totalChapters != null) {
                        MiruSplitProgress(
                            read = readChapters,
                            total = totalChapters,
                            contentDescription = progressDescription,
                            modifier = Modifier
                                .width(splitWidth)
                                .padding(start = 6.dp),
                        )
                    }
                }
            }
            if (hasUpdate) {
                MiruUpdateDot(
                    contentDescription = updateDescription,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverBox(
    title: String,
    rating: String?,
    kind: MiruContentKind?,
    membership: MiruMembershipBadge?,
    selected: Boolean,
    selectionMode: Boolean,
    hasUpdate: Boolean,
    titleOnCover: Boolean,
    membershipDescription: String,
    contentTypeDescription: String,
    updateDescription: String,
    selectionDescription: String,
    bottomSource: String? = null,
    bottomNewChapters: Int = 0,
    cover: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CoverInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspect)
                .clip(CoverShape),
        ) {
            cover()
            if (titleOnCover) {
                OnCoverTitle(title = title)
            }
        }
        if (kind != null && membership == null) {
            MiruContentTypeIcon(
                kind = kind,
                contentDescription = contentTypeDescription,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }
        membership?.let {
            MiruMembershipBookmark(
                badge = it,
                contentDescription = membershipDescription,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (-2).dp)
                    .padding(start = 8.dp),
            )
        }
        if (rating != null && !selected && !selectionMode) {
            MiruRatingLabel(
                text = rating,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
        if (hasUpdate || !bottomSource.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(MiruSpace.S)
                    .fillMaxWidth(0.6f),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            ) {
                if (hasUpdate) {
                    MiruUpdateDot(contentDescription = updateDescription)
                }
                if (!bottomSource.isNullOrBlank()) {
                    MiruSourceLabel(
                        text = bottomSource,
                        newChapters = bottomNewChapters,
                    )
                }
            }
        }
        if (selected) {
            MiruSelectionCheck(
                contentDescription = selectionDescription,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        } else if (selectionMode) {
            MiruSelectionEmpty(
                contentDescription = selectionDescription,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(
    source: String,
    read: Int?,
    total: Int?,
    newChapters: Int,
    progressDescription: String,
    splitWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MiruSourceLabel(
            text = source,
            newChapters = newChapters,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (read != null || total != null) {
            MiruSplitProgress(
                read = read,
                total = total,
                contentDescription = progressDescription,
                modifier = Modifier.width(splitWidth),
            )
        }
    }
}

@Composable
private fun BoxScope.OnCoverTitle(title: String) {
    val extra = LocalMiruExtraColors.current
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to extra.scrim.copy(alpha = 0f),
                    1f to extra.scrim,
                ),
            )
            .padding(top = 20.dp, bottom = 8.dp)
            .padding(start = 8.dp, end = 8.dp),
    ) {
        Text(
            text = title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MiruCardType.Title.copy(
                fontWeight = FontWeight.Bold,
                drawStyle = Stroke(
                    width = with(density) { 3.dp.toPx() },
                    join = StrokeJoin.Round,
                ),
            ),
            color = MaterialTheme.colorScheme.scrim,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
        Text(
            text = title,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MiruCardType.Title.copy(fontWeight = FontWeight.Bold),
            color = extra.onScrim,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
