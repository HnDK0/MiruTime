package dev.mirutime.uikit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// One floating plate header for the app shell and the reader chrome. The
// caller owns navigation/actions and any progress strip, so ui-kit stays
// independent of app/feature resources while both headers keep identical
// height, typography, insets and plate styling.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiruPlateHeader(
    title: String,
    subtitle: String,
    navigation: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    opaque: Boolean = false,
    subtitleFirst: Boolean = false,
    titleMaxLines: Int = 2,
    progress: (@Composable () -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = LocalMiruExtraColors.current.muted
    val largeText = isLargeText()

    val titleBlock: @Composable (Boolean) -> Unit = { large ->
        Column {
            if (subtitleFirst && subtitle.isNotBlank()) {
                HeaderSubtitle(subtitle, soft, large)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitleFirst && subtitle.isNotBlank()) {
                HeaderSubtitle(subtitle, soft, large)
            }
        }
    }

    val bar: @Composable () -> Unit = {
        TopAppBar(
            title = { titleBlock(false) },
            navigationIcon = navigation,
            actions = { actions() },
            // The plate handles the status bar inset, so the bar takes none.
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = ink,
                navigationIconContentColor = ink,
                actionIconContentColor = ink,
            ),
        )
    }
    // At 200% the subtitle must wrap and the plate must grow; TopAppBar's
    // fixed height would clip it. The row keeps the same nav/actions.
    val largeBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                titleBlock(true)
            }
            actions()
        }
    }

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(MiruRadius.Plate),
        // The plate floats over scrolling content: 0.96 keeps the glass tint
        // without letting the list ghost through the title.
        color = if (opaque) {
            MaterialTheme.colorScheme.surface
        } else {
            miruCardSurface().let { if (it.alpha < 1f) it.copy(alpha = 0.96f) else it }
        },
        shadowElevation = miruCardElevation(),
        border = miruPlateBorder(),
        tonalElevation = 0.dp,
    ) {
        Column {
            if (largeText) largeBar() else bar()
            progress?.invoke()
        }
    }
}

@Composable
private fun HeaderSubtitle(
    text: String,
    color: Color,
    largeText: Boolean,
) {
    Text(
        text = text,
        style = if (largeText) {
            MaterialTheme.typography.bodySmall
        } else {
            MaterialTheme.typography.labelSmall
        },
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
