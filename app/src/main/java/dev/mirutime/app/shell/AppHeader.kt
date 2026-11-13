package dev.mirutime.app.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruPlateHeader
import dev.mirutime.uikit.MiruRadius

// Pinned header, always visible, never collapses on scroll: app icon plus
// back on non-main screens, screen title with a description subtitle, theme
// and extras buttons on the right opening bottom sheets.
//
// The header is a floating plate; geometry and ink live in MiruPlateHeader so
// the reader chrome cannot drift from the app shell.
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
    val navigation: @Composable () -> Unit = {
        if (!isMainScreen) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
    MiruPlateHeader(
        title = title,
        subtitle = description,
        navigation = navigation,
        actions = actions,
        opaque = opaque,
    )
}
