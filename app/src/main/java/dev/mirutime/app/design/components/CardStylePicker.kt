package dev.mirutime.app.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.uikit.MiruBookCard
import dev.mirutime.uikit.MiruContentKind
import dev.mirutime.uikit.MiruSpace

// One visual owner for the card-style choice. It is a horizontal rail (the
// same pattern as the theme picker / global-search rails) and applies on tap;
// there is no Save. Used by the library filter sheet and Settings -> Library.
@Composable
fun CardStylePicker(
    current: CardStyle,
    onSelect: (CardStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPreset = current.toPreset()
    val listState = rememberLazyListState()
    val currentIndex = GridStyles.indexOfFirst { it.toPreset() == currentPreset }
        .coerceAtLeast(0)
    LaunchedEffect(currentPreset) {
        if (currentIndex in GridStyles.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
    ) {
        items(GridStyles, key = { it.name }) { style ->
            Column(
                modifier = Modifier.width(120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MiruBookCard(
                    preset = style.toPreset(),
                    title = stringResource(R.string.card_preview_title),
                    source = stringResource(R.string.card_preview_source),
                    rating = "4.5",
                    readChapters = 24,
                    totalChapters = 120,
                    kind = MiruContentKind.Novel,
                    selected = currentPreset == style.toPreset(),
                    onClick = { onSelect(style) },
                    contentTypeDescription = "",
                    updateDescription = "",
                    selectionDescription = "",
                    modifier = Modifier.width(120.dp),
                    cover = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.card_preview_title)
                                    .firstOrNull()?.uppercase() ?: "A",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
                Text(
                    text = cardStyleLabel(style),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .width(120.dp)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}
