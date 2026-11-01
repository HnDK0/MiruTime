package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// Duration with localized units (shared by the dashboard panels).
@Composable
internal fun formatDurationLocalized(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    val min = stringResource(R.string.dash_unit_min)
    val h = stringResource(R.string.dash_unit_h)
    if (minutes < 60) return "$minutes $min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours $h" else "$hours $h $rest $min"
}

private val WeekBarArea = 56.dp
private val WeekBarMax = 26.dp

// "Today" panel, one plate, three reads from top to bottom:
//   1. the total (big) + how it compares with yesterday,
//   2. how it splits between reading and listening (one bar + legend),
//   3. the last 7 days as small bars, today highlighted, with the daily mean.
// Inks are the plate-verified ones only: accent for read / today, muted for
// listened / other days, so contrast holds on photo themes too.
@Composable
fun TimeStatsPanel(
    stats: ReadingStats,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val total = stats.today.totalMs
    // No plate of its own: the dashboard puts it inside the "Today" section
    // card (header + hairline + this), like every other titled section.
    run {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        ) {
            // 1. Total + delta
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dash_stat_today_total),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                    Text(
                        text = formatDurationLocalized(total),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DeltaLabel(todayMs = total, yesterdayMs = stats.yesterdayMs)
            }

            // 2. Read / listened split
            Spacer(modifier = Modifier.height(MiruSpace.L))
            SplitBar(readMs = stats.today.readMs, listenMs = stats.today.listenMs)
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.L)) {
                LegendItem(
                    color = extra.accent,
                    value = formatDurationLocalized(stats.today.readMs),
                    label = stringResource(R.string.dash_stat_read),
                )
                LegendItem(
                    color = extra.muted,
                    value = formatDurationLocalized(stats.today.listenMs),
                    label = stringResource(R.string.dash_stat_listened),
                )
            }

            // 3. Last 7 days
            Spacer(modifier = Modifier.height(MiruSpace.L))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(MiruSpace.M))
            WeekChart(weekMs = stats.weekMs)
        }
    }
}

// "+29 min vs yesterday": accent when up, muted when down or equal. Hidden
// when there is no yesterday to compare with.
@Composable
private fun DeltaLabel(todayMs: Long, yesterdayMs: Long) {
    if (yesterdayMs <= 0L) return
    val delta = todayMs - yesterdayMs
    val sign = when {
        delta > 0 -> "+"
        delta < 0 -> "−"
        else -> "±"
    }
    val value = if (delta == 0L) formatDurationLocalized(0) else formatDurationLocalized(abs(delta))
    Text(
        text = stringResource(R.string.dash_stat_vs_yesterday_fmt, sign + value),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (delta > 0) LocalMiruExtraColors.current.accent else LocalMiruExtraColors.current.muted,
        maxLines = 1,
        modifier = Modifier.padding(start = MiruSpace.M, bottom = MiruSpace.Xs),
    )
}

// One bar, two segments sized by share. With nothing logged yet it is just
// the empty track.
@Composable
private fun SplitBar(readMs: Long, listenMs: Long) {
    val extra = LocalMiruExtraColors.current
    val total = readMs + listenMs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    ) {
        if (total > 0) {
            if (readMs > 0) {
                Box(
                    modifier = Modifier
                        .weight(readMs.toFloat())
                        .height(8.dp)
                        .background(extra.accent),
                )
            }
            if (listenMs > 0) {
                if (readMs > 0) Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .weight(listenMs.toFloat())
                        .height(8.dp)
                        .background(extra.muted),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(MiruSpace.S))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun WeekChart(weekMs: List<Long>) {
    if (weekMs.isEmpty()) return
    val extra = LocalMiruExtraColors.current
    val max = weekMs.max().coerceAtLeast(1L)
    val average = weekMs.sum() / weekMs.size
    // Day names ending today; recomputed only when the locale changes.
    val locale = LocalLocale.current.platformLocale
    val days = remember(weekMs.size, locale) {
        val today = LocalDate.now()
        List(weekMs.size) { i ->
            today.minusDays((weekMs.size - 1 - i).toLong())
                .dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.dash_week_title),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.dash_week_avg_fmt, formatDurationLocalized(average)),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            maxLines = 1,
        )
    }
    Spacer(modifier = Modifier.height(MiruSpace.M))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        weekMs.forEachIndexed { index, ms ->
            val isToday = index == weekMs.lastIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WeekBarArea),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Empty day = a short neutral stub, never an invisible bar.
                    val barHeight = if (ms <= 0L) {
                        4.dp
                    } else {
                        (WeekBarArea * (ms.toFloat() / max)).coerceAtLeast(6.dp)
                    }
                    Box(
                        modifier = Modifier
                            .width(WeekBarMax)
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(
                                when {
                                    ms <= 0L -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                                    isToday -> extra.accent
                                    else -> extra.muted.copy(alpha = 0.55f)
                                },
                            ),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.S))
                Text(
                    text = days[index],
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isToday) MaterialTheme.colorScheme.onSurface else extra.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
