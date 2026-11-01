package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.mirutime.app.BuildConfig
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.ChoiceSheet
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsHint
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.app.features.library.data.LibraryUpdateScheduler
import dev.mirutime.app.features.library.data.LibraryUpdateStatus
import dev.mirutime.app.design.components.CardStylePicker
import dev.mirutime.app.design.components.cardStyleLabel
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.updates.data.AppReleaseFetch
import dev.mirutime.app.features.updates.data.AppUpdateChecker
import dev.mirutime.app.features.updates.data.AppUpdateResult

// Library: how the library and catalogues lay out their books. These keys are
// shared by the library and the catalogue grids (BOOKS_GRID_COLUMNS applies to
// both). Values are stored; the screens read them once the data layer lands.
@Composable
fun LibrarySettingsScreen(contentPadding: PaddingValues) {
    var layoutSheet by remember { mutableStateOf(false) }
    var cardStyleSheet by remember { mutableStateOf(false) }
    var orderSheet by remember { mutableStateOf(false) }
    val s = AppSettings

    SettingsPage(contentPadding) {
        item(key = "grid") {
            MiruSectionCard(
                title = stringResource(R.string.st_library_grid),
                description = stringResource(R.string.st_library_grid_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_library_layout),
                    value = stringResource(
                        if (s.listLayout == "grid") R.string.st_layout_grid else R.string.st_layout_list,
                    ),
                    onClick = { layoutSheet = true },
                )
                MiruRowDivider()
                SettingsSliderRow(
                    title = stringResource(R.string.st_library_columns),
                    subtitle = stringResource(R.string.st_library_columns_desc),
                    valueText = s.gridColumns.toString(),
                    value = s.gridColumns.toFloat(),
                    range = 2f..6f,
                    steps = 3,
                    onChange = { s.gridColumns = it.toInt() },
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.card_style_title),
                    subtitle = stringResource(R.string.card_style_desc),
                    value = cardStyleLabel(s.cardStyle),
                    onClick = { cardStyleSheet = true },
                )
            }
        }
        item(key = "chapters") {
            MiruSectionCard(
                title = stringResource(R.string.st_library_chapters),
                description = stringResource(R.string.st_library_chapters_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_library_chapter_order),
                    value = stringResource(
                        if (s.newestFirst) R.string.st_order_newest else R.string.st_order_oldest,
                    ),
                    onClick = { orderSheet = true },
                )
            }
        }
    }

    if (layoutSheet) {
        ChoiceSheet(
            title = stringResource(R.string.st_library_layout),
            description = stringResource(R.string.st_library_layout_desc),
            options = listOf(
                ChoiceOption("grid", stringResource(R.string.st_layout_grid)),
                ChoiceOption("list", stringResource(R.string.st_layout_list)),
            ),
            selected = s.listLayout,
            onSelect = { s.listLayout = it },
            onClose = { layoutSheet = false },
        )
    }
    if (cardStyleSheet) {
        AppBottomSheet(
            title = stringResource(R.string.card_style_title),
            description = stringResource(R.string.card_style_desc),
            onClose = { cardStyleSheet = false },
        ) {
            CardStylePicker(
                current = s.cardStyle,
                onSelect = { s.cardStyle = it },
            )
        }
    }
    if (orderSheet) {
        ChoiceSheet(
            title = stringResource(R.string.st_library_chapter_order),
            description = stringResource(R.string.st_library_chapter_order_desc),
            options = listOf(
                ChoiceOption(true, stringResource(R.string.st_order_newest)),
                ChoiceOption(false, stringResource(R.string.st_order_oldest)),
            ),
            selected = s.newestFirst,
            onSelect = { s.newestFirst = it },
            onClose = { orderSheet = false },
        )
    }
}

@Composable
// Updates: the app's own update check and the periodic library update.
// Intervals are our menu (on launch / 12 h / 24 h / 72 h / week): nothing
// faster than 12 h, Doze would smear it anyway.
fun UpdatesSettingsScreen(contentPadding: PaddingValues) {
    val s = AppSettings
    val context = LocalContext.current
    val libraryStatus by LibraryUpdateScheduler.status.collectAsState()
    var intervalSheet by remember { mutableStateOf(false) }
    var checkOpen by remember { mutableStateOf(false) }
    val appUpdateFetch = remember(context) {
        val graph = (context.applicationContext as MiruApp).pluginGraph
        AppReleaseFetch { url, headers, timeoutMs ->
            graph.fetchRemote(url, headers, timeoutMs, followRedirects = true)
        }
    }

    // Immediate reschedule on every change; the debounced settings writer also
    // syncs, but the user should not wait for it.
    LaunchedEffect(
        s.libraryAutoUpdate,
        s.libraryIntervalHours,
        s.updateWifiOnly,
        s.updateWhileCharging,
    ) {
        LibraryUpdateScheduler.sync(context, AppSettings.persistedSnapshot())
    }

    SettingsPage(contentPadding) {
        item(key = "app") {
            MiruSectionCard(
                title = stringResource(R.string.st_updates_app),
                description = stringResource(R.string.st_updates_app_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_updates_app_auto),
                    subtitle = stringResource(R.string.st_updates_app_auto_desc),
                    checked = s.appUpdateCheck,
                    onChange = { s.appUpdateCheck = it },
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.st_updates_check_now),
                    value = "",
                    onClick = { checkOpen = true },
                )
            }
        }
        item(key = "library") {
            MiruSectionCard(
                title = stringResource(R.string.st_updates_library),
                description = stringResource(R.string.st_updates_library_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_updates_library_auto),
                    checked = s.libraryAutoUpdate,
                    onChange = { s.libraryAutoUpdate = it },
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.st_updates_interval),
                    value = intervalLabel(s.libraryIntervalHours),
                    onClick = { intervalSheet = true },
                    enabled = s.libraryAutoUpdate,
                )
                MiruRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.st_updates_wifi_only),
                    subtitle = stringResource(R.string.st_updates_wifi_only_desc),
                    checked = s.updateWifiOnly,
                    onChange = { s.updateWifiOnly = it },
                    enabled = s.libraryAutoUpdate,
                )
                MiruRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.st_updates_charging),
                    subtitle = stringResource(R.string.st_updates_charging_desc),
                    checked = s.updateWhileCharging,
                    onChange = { s.updateWhileCharging = it },
                    enabled = s.libraryAutoUpdate,
                )
                SettingsHint(stringResource(R.string.st_updates_hint))
                libraryStatusText(libraryStatus)?.let { SettingsHint(it) }
            }
        }
    }

    if (intervalSheet) {
        ChoiceSheet(
            title = stringResource(R.string.st_updates_interval),
            description = stringResource(R.string.st_updates_interval_desc),
            options = listOf(0, 12, 24, 72, 168).map { ChoiceOption(it, intervalLabel(it)) },
            selected = s.libraryIntervalHours,
            onSelect = { s.libraryIntervalHours = it },
            onClose = { intervalSheet = false },
        )
    }
    if (checkOpen) {
        var attempt by remember { mutableIntStateOf(0) }
        var checking by remember { mutableStateOf(true) }
        var result by remember { mutableStateOf<AppUpdateResult?>(null) }
        val uriHandler = LocalUriHandler.current
        LaunchedEffect(attempt) {
            checking = true
            result = AppUpdateChecker(appUpdateFetch).check(BuildConfig.VERSION_NAME)
            checking = false
        }
        AppBottomSheet(
            title = stringResource(R.string.st_updates_check_now),
            description = stringResource(R.string.st_updates_check_desc),
            onClose = { checkOpen = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val message = if (checking) {
                    stringResource(R.string.st_updates_checking)
                } else {
                    when (val current = result) {
                        is AppUpdateResult.Available -> stringResource(
                            R.string.st_updates_available_fmt,
                            current.version,
                            BuildConfig.VERSION_NAME,
                        )
                        is AppUpdateResult.UpToDate -> stringResource(
                            R.string.st_updates_latest_fmt,
                            BuildConfig.VERSION_NAME,
                        )
                        AppUpdateResult.NoReleases -> stringResource(R.string.st_updates_no_releases)
                        // The raw transport message carries the full API URL:
                        // never render it. The detail goes to MiruLog instead.
                        is AppUpdateResult.Error -> stringResource(
                            updateResultMessageRes(current),
                        )
                        null -> stringResource(R.string.st_updates_checking)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Real indeterminate spinner next to the status text; the
                    // check is a network round-trip, not instant.
                    if (checking) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = LocalMiruExtraColors.current.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (checking) {
                            LocalMiruExtraColors.current.muted
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                when (val current = result) {
                    is AppUpdateResult.Available -> TextButton(
                        onClick = { uriHandler.openUri(current.url) },
                    ) {
                        Text(stringResource(R.string.st_updates_open_release))
                    }
                    is AppUpdateResult.Error -> TextButton(onClick = { attempt++ }) {
                        Text(stringResource(R.string.st_updates_retry))
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Stable resource for the check result; failure text is never transport text. */
@StringRes
internal fun updateResultMessageRes(result: AppUpdateResult?): Int = when (result) {
    is AppUpdateResult.Available -> R.string.st_updates_available_fmt
    is AppUpdateResult.UpToDate -> R.string.st_updates_latest_fmt
    AppUpdateResult.NoReleases -> R.string.st_updates_no_releases
    is AppUpdateResult.Error -> R.string.st_updates_error
    null -> R.string.st_updates_checking
}

@Composable
private fun libraryStatusText(status: LibraryUpdateStatus): String? {
    val res = libraryStatusRes(status) ?: return null
    return when (status) {
        is LibraryUpdateStatus.LastRun -> stringResource(res, status.checked, status.updated, status.failed)
        else -> stringResource(res)
    }
}

/** Stable resource per status; transport text is never rendered. */
@StringRes
internal fun libraryStatusRes(status: LibraryUpdateStatus): Int? = when (status) {
    LibraryUpdateStatus.Idle -> null
    LibraryUpdateStatus.Disabled -> R.string.st_updates_status_off
    LibraryUpdateStatus.Scheduled -> R.string.st_updates_status_scheduled
    is LibraryUpdateStatus.LastRun -> R.string.st_updates_status_last_fmt
    is LibraryUpdateStatus.Error -> R.string.st_updates_status_error
}
