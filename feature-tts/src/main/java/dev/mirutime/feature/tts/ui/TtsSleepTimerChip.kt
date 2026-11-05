package dev.mirutime.feature.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.TtsSleepTimerMode
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.formatTtsDuration
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "14:32" for minutes, "2 ch." for chapters; shared by every TTS surface. */
@Composable
fun ttsSleepTimerLabel(timer: TtsSleepTimerState): String = when (timer.mode) {
    TtsSleepTimerMode.Minutes -> formatTtsDuration(timer.remainingSeconds)
    TtsSleepTimerMode.Chapters -> stringResource(
        R.string.rd_tts_sleep_timer_chapters_value_fmt,
        timer.chaptersRemaining,
    )
}

@Composable
fun ttsSleepTimerDescription(timer: TtsSleepTimerState): String =
    stringResource(R.string.rd_tts_sleep_timer_active_fmt, ttsSleepTimerLabel(timer))

/**
 * The single time chip of the floating player: the sleep countdown replaces the
 * listening estimate while a timer runs. Collecting here keeps the 1 Hz tick
 * local to this chip.
 */
@Composable
fun TtsFloatingTimerChip(
    sleepTimer: StateFlow<TtsSleepTimerState?>?,
    listeningLabel: String,
    listeningDescription: String,
    onSleepTimerClick: (() -> Unit)?,
) {
    // Unconditional collect: the slot table must not depend on nullability.
    val empty = remember { MutableStateFlow<TtsSleepTimerState?>(null) }
    val timer by (sleepTimer ?: empty).collectAsState()
    val timerState = timer
    if (timerState == null) {
        SleepTimerChipSurface(
            label = listeningLabel,
            description = listeningDescription,
            onClick = null,
        )
        return
    }
    SleepTimerChipSurface(
        label = ttsSleepTimerLabel(timerState),
        description = ttsSleepTimerDescription(timerState),
        onClick = onSleepTimerClick,
    )
}

@Composable
private fun SleepTimerChipSurface(
    label: String,
    description: String,
    onClick: (() -> Unit)?,
) {
    val modifier = if (onClick == null) {
        Modifier.clearAndSetSemantics { contentDescription = description }
    } else {
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
            // Keep the click action in semantics; clearAndSetSemantics would
            // erase it along with the text.
            .semantics { contentDescription = description }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(MiruRadius.Badge),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = MiruSpace.Xs, vertical = MiruSpace.Xxs),
            )
        }
    }
}
