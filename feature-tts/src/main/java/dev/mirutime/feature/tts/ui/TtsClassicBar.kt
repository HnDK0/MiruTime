package dev.mirutime.feature.tts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.TtsStatus

/**
 * QuickNovel-style TTS bar: one full-width strip with four evenly spaced icon
 * buttons: rewind (previous sentence), stop, play/pause, fast-forward (next
 * sentence). No text, no cards; the page behind keeps its position.
 */
@Composable
fun TtsClassicBar(
    status: TtsStatus,
    onPrevious: () -> Unit,
    onStop: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = status == TtsStatus.Playing || status == TtsStatus.Loading
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClassicBarButton(
                Icons.Filled.FastRewind,
                stringResource(R.string.rd_tts_prev_chunk),
                Modifier.weight(1f),
                onPrevious,
            )
            ClassicBarButton(
                Icons.Filled.Stop,
                stringResource(R.string.rd_tts_stop),
                Modifier.weight(1f),
                onStop,
            )
            ClassicBarButton(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(
                    if (playing) R.string.rd_tts_action_pause else R.string.rd_tts_action_play,
                ),
                Modifier.weight(1f),
                onToggle,
            )
            ClassicBarButton(
                Icons.Filled.FastForward,
                stringResource(R.string.rd_tts_next_chunk),
                Modifier.weight(1f),
                onNext,
            )
        }
    }
}

@Composable
private fun ClassicBarButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}
