package dev.mirutime.uikit

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Section header with a description subtitle: a PLATE
// in every theme (title + description + action on one surface, 20dp radius),
// so headers read the same on a photo and on a classic palette. The action is
// a 48dp hit box with a text-only look; its ink is the accent, guaranteed
// readable on the plate.
@Composable
fun SectionHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    // Optional free slot at the row end (e.g. a sort toggle); sits after the
    // text action and brings its own 48dp hit box.
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(MiruRadius.Card),
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        border = miruPlateBorder(),
        tonalElevation = 0.dp,
    ) {
        HeaderRow(
            title = title,
            description = description,
            actionLabel = actionLabel,
            onAction = onAction,
            trailing = trailing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MiruSize.Touch)
                .padding(start = MiruSpace.L, end = MiruSpace.Xs, top = MiruSpace.S, bottom = MiruSpace.S),
        )
    }
}

// === Section card ===
// THE standard block for a titled section: header (title + optional
// description + optional action / trailing slot), a hairline, then the
// content — all on ONE plate, the same way the book header holds its genre
// tags under a divider. A section is one object on screen instead of a
// title plate floating above a separate content plate.
//
// The header keeps the 48dp hit box for its action; the divider is the same
// hairline as every other in-card divider (outline). Content gets the shared
// card padding unless the caller needs edge-to-edge rows (lists pass
// PaddingValues(0.dp) and pad their own rows).
@Composable
fun MiruSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(MiruSpace.L),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MiruRadius.Card),
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HeaderRow(
                title = title,
                description = description,
                actionLabel = actionLabel,
                onAction = onAction,
                trailing = trailing,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MiruSize.Touch)
                    .padding(
                        start = MiruSpace.L,
                        end = if (actionLabel != null || trailing != null) MiruSpace.Xs else MiruSpace.L,
                        top = MiruSpace.S,
                        bottom = MiruSpace.S,
                    ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
private fun HeaderRow(
    title: String,
    description: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val maxActionWidth = maxWidth / 2
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Spacer(Modifier.height(MiruSpace.Xxs))
                    Text(description, style = MaterialTheme.typography.bodySmall, color = LocalMiruExtraColors.current.muted)
                }
            }
            if (actionLabel != null && onAction != null) {
                MiruButton(
                    onClick = onAction,
                    modifier = Modifier.widthIn(max = maxActionWidth),
                    style = MiruButtonStyle.Text,
                ) {
                    Text(actionLabel)
                }
            }
            trailing?.invoke()
        }
    }
}
