package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.isLargeText
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import java.time.LocalDate
import java.time.format.TextStyle
import dev.mirutime.uikit.LocalMiruLocale
import dev.mirutime.uikit.miruDecimal
import kotlin.math.abs

// Duration with localized units (shared by the dashboard panels).
@Composable
internal fun formatDurationLocalized(ms: Long): String {
    val locale = LocalMiruLocale.current
    val minutes = (ms / 60_000).toInt()
    val min = stringResource(R.string.dash_unit_min)
    val h = stringResource(R.string.dash_unit_h)
    if (minutes < 60) return "${miruDecimal(minutes, 0, locale)} $min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) {
        "${miruDecimal(hours, 0, locale)} $h"
    } else {
        "${miruDecimal(hours, 0, locale)} $h ${miruDecimal(rest, 0, locale)} $min"
    }
}

private val WeekBarArea = 32.dp
private val WeekBarMax = 16.dp

// Compact "Today" Bento stats block (no standalone outer plate; hosted inside a
// MiruCard at the top of Dashboard):
//   1. TODAY eyebrow + delta alongside total time and compact read/TTS legend,
//   2. 5dp split bar,
//   3. 7-day spark-chart (32dp bars) with daily mean.
@Composable
fun TimeStatsPanel(
    stats: ReadingStats,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val total = stats.today.totalMs
    val largeText = isLargeText()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs)) {
                Text(
                    text = stringResource(R.string.dash_today),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.accent,
                )
                DeltaLabel(todayMs = total, yesterdayMs = stats.yesterdayMs)
            }
            Spacer(modifier = Modifier.height(MiruSpace.Xxs))
            Text(
                text = formatDurationLocalized(total),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs)) {
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
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxLegendWidth = maxWidth * 0.48f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                        ) {
                            Text(
                                text = stringResource(R.string.dash_today),
                                style = MaterialTheme.typography.labelSmall,
                                color = extra.accent,
                                maxLines = 1,
                            )
                            DeltaLabel(todayMs = total, yesterdayMs = stats.yesterdayMs)
                        }
                        Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                        Text(
                            text = formatDurationLocalized(total),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                        modifier = Modifier
                            .widthIn(max = maxLegendWidth)
                            .padding(start = MiruSpace.S),
                    ) {
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
                }
            }
        }

        Spacer(modifier = Modifier.height(MiruSpace.S))
        SplitBar(readMs = stats.today.readMs, listenMs = stats.today.listenMs)

        Spacer(modifier = Modifier.height(MiruSpace.S))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(MiruSpace.S))
        WeekChart(weekMs = stats.weekMs)
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
        maxLines = if (isLargeText()) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// One slim bar, two segments sized by share. With nothing logged yet it is just
// the empty track.
@Composable
private fun SplitBar(readMs: Long, listenMs: Long) {
    val extra = LocalMiruExtraColors.current
    val total = readMs + listenMs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(MiruRadius.Split))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    ) {
        if (total > 0) {
            if (readMs > 0) {
                Box(
                    modifier = Modifier
                        .weight(readMs.toFloat())
                        .height(5.dp)
                        .background(extra.accent),
                )
            }
            if (listenMs > 0) {
                if (readMs > 0) Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .weight(listenMs.toFloat())
                        .height(5.dp)
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
                .size(6.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(MiruSpace.Xs))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(MiruSpace.Xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

    val avgText = stringResource(R.string.dash_week_avg_fmt, formatDurationLocalized(average))
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs)) {
            Text(
                text = stringResource(R.string.dash_week_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = avgText,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxAvgWidth = maxWidth / 2
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.dash_week_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = avgText,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = maxAvgWidth)
                        .padding(start = MiruSpace.S),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(MiruSpace.Xs))
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
                        3.dp
                    } else {
                        (WeekBarArea * (ms.toFloat() / max)).coerceAtLeast(5.dp)
                    }
                    Box(
                        modifier = Modifier
                            .width(WeekBarMax)
                            .height(barHeight)
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .background(
                                when {
                                    ms <= 0L -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                                    isToday -> extra.accent
                                    else -> extra.muted.copy(alpha = 0.55f)
                                },
                            ),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                Text(
                    text = days[index],
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isToday) extra.accent else extra.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
