package dev.mirutime.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.isLargeText
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.uikit.miruPlateBorder

// Pinned header, always visible, never collapses on scroll: app icon plus
// back on non-main screens, screen title with a description subtitle, theme
// and extras buttons on the right opening bottom sheets.
//
// The header is a FLOATING PLATE in every theme — 24dp radius, 12dp side
// margin — and all of its ink (title, subtitle, icons) comes from the plate
// roles (onSurface / muted), which the theme builder verifies against the
// plate (over black and white on a photo). No bare text over the picture.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    title: String,
    description: String,
    isMainScreen: Boolean,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onExtras: () -> Unit,
    opaque: Boolean = false,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = LocalMiruExtraColors.current.muted
    val navigation: @Composable () -> Unit = {
        if (!isMainScreen) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.header_back),
                    tint = ink,
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.miru_logo),
                contentDescription = stringResource(R.string.header_app_icon),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(32.dp)
                    .clip(RoundedCornerShape(MiruRadius.Badge)),
            )
        }
    }
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTheme) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = stringResource(R.string.header_theme),
                    tint = ink,
                )
            }
            IconButton(onClick = onExtras) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.header_more),
                    tint = ink,
                )
            }
        }
    }
    val bar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = soft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = navigation,
            actions = { actions() },
            // The plate already handles the status bar inset (windowInsetsPadding
            // on the wrapper below), so the bar itself takes none inside it.
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
    val largeText = isLargeText()
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
    Surface(
        modifier = Modifier
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
    ) { if (largeText) largeBar() else bar() }
}
