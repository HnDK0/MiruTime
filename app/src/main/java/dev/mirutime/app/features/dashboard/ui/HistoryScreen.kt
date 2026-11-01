package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import dev.mirutime.app.features.dashboard.data.DashboardDataProvider
import dev.mirutime.app.features.dashboard.domain.HistoryEntry

// Dedicated History screen (drill-in from the dashboard's "Show all
// history"): every recent read, same fake data, rows shared with the rail.
// Back returns to the dashboard via the shell header — standard back
// behavior, mirroring the Updates drill-in.
@Composable
fun HistoryScreen(
    contentPadding: PaddingValues,
    provider: DashboardDataProvider,
    onBookOpen: (String) -> Unit = {},
) {
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    // Skeleton rows until fullHistory() returns; the empty history card must
    // not flash before the first Room read.
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        try {
            history = provider.fullHistory()
        } finally {
            loaded = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        if (!loaded) {
            items(6) {
                HistoryRowSkeleton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
        } else {
            itemsIndexed(
                history,
                key = { index, entry -> "history:${entry.book.bookUrl}:$index" },
            ) { _, entry ->
                MiruCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    HistoryRow(
                        entry = entry,
                        onOpen = { onBookOpen(entry.book.bookUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRowSkeleton(modifier: Modifier = Modifier) {
    MiruCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .padding(start = CoverInset, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(width = 48.dp, height = 72.dp, corner = MiruRadius.Cover)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.75f), height = 12.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.5f), height = 10.dp)
                Spacer(modifier = Modifier.height(10.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.65f), height = 10.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 4.dp)
            }
        }
    }
}

// Shared history row: 48x72 cover inside a 4dp frame, title (2 lines),
// source + read time, ratio + progress, accent "Open". Same family as
// UpdateRow; plate ink only.
@Composable
fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit = {},
) {
    val accent = LocalMiruExtraColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruRadius.Inner))
            .clickable(onClick = onOpen)
            .defaultMinSize(minHeight = 72.dp)
            .padding(start = CoverInset, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            title = entry.book.translatedTitle ?: entry.book.title,
            coverUrl = entry.book.coverUrl,
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .padding(CoverInset),
            corner = MiruRadius.Cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = entry.book.translatedTitle ?: entry.book.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.book.sourceLabel} · ${entry.readLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(MiruSpace.S))
            Text(
                text = "${stringResource(R.string.card_next_ch_fmt, entry.chapter)} · " +
                    stringResource(
                        R.string.card_ratio_fmt,
                        entry.book.readChapters,
                        entry.book.totalChapters,
                    ),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            CardProgressBar(
                fraction = entry.book.readChapters.toFloat() /
                    entry.book.totalChapters.coerceAtLeast(1),
            )
        }
        Text(
            text = stringResource(R.string.dash_open),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
}
