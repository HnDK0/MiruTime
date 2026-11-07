package dev.mirutime.app.features.settings.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsHint
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsTextField
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.BatteryOptimization
import dev.mirutime.app.features.settings.data.BatteryOptimizationState
import dev.mirutime.app.features.settings.data.StorageBucket
import dev.mirutime.app.features.settings.data.StorageUsage
import dev.mirutime.app.features.translation.data.LocalNetworkAccess
import kotlinx.coroutines.launch

// Network: scraper identity, Cloudflare, polite delays and the background-work
// (battery) switch. Mirrors NoveLA's section; the dead
// CLOUDFLARE_CHALLENGE_TIMEOUT key is intentionally absent, and WTR-Lab source
// options now live in the plugin's own settings sheet.
@Composable
fun NetworkSettingsScreen(contentPadding: PaddingValues) {
    val s = AppSettings
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The system, not a saved flag, owns this state; refresh on every resume
    // (the opt-out dialog is a separate activity).
    var battery by remember(context) { mutableStateOf(BatteryOptimization.status(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                battery = BatteryOptimization.status(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // NoveLA rule: the header value must be plain Latin text.
    val uaError = if (s.userAgent.all { it.code in 0x20..0x7E }) {
        null
    } else {
        stringResource(R.string.st_network_ua_invalid)
    }
    // One entry point for the Android 17+ local-network grant: LAN repo,
    // translation and video all depend on it, and the row hides once granted.
    val localNetwork = rememberLocalNetworkGrant()
    val showLocalNetwork =
        Build.VERSION.SDK_INT >= LocalNetworkAccess.RequiredApi && !localNetwork.granted

    SettingsPage(contentPadding) {
        item(key = "requests") {
            MiruSectionCard(
                title = stringResource(R.string.st_network_requests),
                description = stringResource(R.string.st_network_requests_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_network_cloudflare),
                    subtitle = stringResource(R.string.st_network_cloudflare_desc),
                    checked = s.cloudflareBypass,
                    onChange = { s.cloudflareBypass = it },
                )
                MiruRowDivider()
                SettingsTextField(
                    label = stringResource(R.string.st_network_ua),
                    value = s.userAgent,
                    onChange = { s.userAgent = it },
                    placeholder = stringResource(R.string.st_network_ua_empty),
                    tip = stringResource(R.string.st_network_ua_tip),
                    error = uaError,
                    singleLine = false,
                    maxLines = 3,
                )
                if (showLocalNetwork) {
                    MiruRowDivider()
                    SettingsValueRow(
                        title = stringResource(R.string.st_tr_local_network_allow),
                        subtitle = stringResource(
                            if (localNetwork.denied) {
                                R.string.st_net_local_network_denied
                            } else {
                                R.string.st_net_local_network_desc
                            },
                        ),
                        value = stringResource(R.string.st_tr_local_network_grant),
                        onClick = localNetwork.request,
                    )
                }
            }
        }
        item(key = "delays") {
            MiruSectionCard(
                title = stringResource(R.string.st_network_delays),
                description = stringResource(R.string.st_network_delays_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSliderRow(
                    title = stringResource(R.string.st_network_download_delay),
                    subtitle = stringResource(R.string.st_network_download_delay_desc),
                    valueText = stringResource(R.string.st_ms_fmt, s.downloadDelayMs),
                    value = s.downloadDelayMs.toFloat(),
                    range = 0f..10_000f,
                    steps = 39,
                    onChange = { s.downloadDelayMs = it.toInt() },
                )
            }
        }
        item(key = "background") {
            MiruSectionCard(
                title = stringResource(R.string.st_network_background),
                description = stringResource(R.string.st_network_battery_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_network_battery),
                    subtitle = stringResource(
                        when (battery.state) {
                            BatteryOptimizationState.Ignored -> R.string.st_network_battery_off
                            BatteryOptimizationState.Optimized -> R.string.st_network_battery_on
                            BatteryOptimizationState.Unsupported ->
                                R.string.st_network_battery_unavailable
                        },
                    ),
                    value = stringResource(
                        when (battery.state) {
                            BatteryOptimizationState.Ignored -> R.string.st_status_ok
                            BatteryOptimizationState.Optimized -> R.string.st_status_fix
                            BatteryOptimizationState.Unsupported -> R.string.st_status_unavailable
                        },
                    ),
                    onClick = {
                        // Never flip local state: only the system dialog can
                        // change it, and the next resume reads the result.
                        val acted = if (battery.ignoring) {
                            BatteryOptimization.openSettings(context)
                        } else {
                            BatteryOptimization.requestIgnore(context)
                        }
                        if (!acted) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.st_network_battery_unsupported),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

// Data: what the app keeps on the device, with a Clear button per bucket and
// a confirmation sheet (clearing the database drops everything that is not in
// the library, so it is the loudest one).
@Composable
fun DataSettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val storage = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).storageUsageService
    }
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf(StorageUsage.Empty) }
    var loading by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<StorageBucket?>(null) }
    var cleaning by remember { mutableStateOf<StorageBucket?>(null) }
    var outOfLibraryConfirm by remember { mutableStateOf(false) }
    var purgingOutOfLibrary by remember { mutableStateOf(false) }

    LaunchedEffect(storage) {
        usage = runCatching { storage.refresh() }.getOrDefault(StorageUsage.Empty)
        loading = false
    }

    SettingsPage(contentPadding) {
        item(key = "storage") {
            MiruSectionCard(
                title = stringResource(R.string.st_data_storage),
                description = stringResource(R.string.st_data_total_fmt, formatBytes(usage.totalBytes)),
                contentPadding = PaddingValues(0.dp),
            ) {
                StorageBucket.entries.forEachIndexed { index, bucket ->
                    if (index > 0) MiruRowDivider()
                    val size = usage.bytesFor(bucket)
                    val clearable = usage.isClearable(bucket)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = MiruSize.Row)
                            .padding(start = MiruSpace.L, end = MiruSpace.S),
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(bucketTitle(bucket)),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.st_data_size_fmt, formatBytes(size)),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalMiruExtraColors.current.muted,
                            )
                        }
                        when {
                            loading -> CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = LocalMiruExtraColors.current.accent,
                                modifier = Modifier
                                    .padding(end = MiruSpace.M)
                                    .padding(MiruSpace.Xs),
                            )

                            cleaning == bucket -> CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = LocalMiruExtraColors.current.accent,
                                modifier = Modifier
                                    .padding(end = MiruSpace.M)
                                    .padding(MiruSpace.Xs),
                            )

                            size > 0L && !clearable -> Text(
                                text = stringResource(R.string.st_data_not_supported),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalMiruExtraColors.current.muted,
                                modifier = Modifier.padding(end = MiruSpace.M),
                            )

                            else -> TextButton(
                                onClick = { confirm = bucket },
                                enabled = size > 0L && cleaning == null,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.st_data_clear),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
                SettingsHint(stringResource(R.string.st_data_hint))
            }
        }
        item(key = "out-of-library") {
            MiruSectionCard(
                title = stringResource(R.string.st_data_out_of_library),
                description = stringResource(R.string.st_data_out_of_library_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = MiruSize.Row)
                        .padding(start = MiruSpace.L, end = MiruSpace.S),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (loading) {
                                stringResource(R.string.st_data_loading)
                            } else {
                                stringResource(
                                    R.string.st_data_out_of_library_count,
                                    usage.outOfLibraryBookCount,
                                    usage.orphanBookDirCount,
                                    formatBytes(usage.orphanBookBytes),
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (purgingOutOfLibrary) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = LocalMiruExtraColors.current.accent,
                            modifier = Modifier.padding(MiruSpace.Xs),
                        )
                    } else {
                        TextButton(
                            onClick = { outOfLibraryConfirm = true },
                            enabled = usage.outOfLibraryBookCount > 0 ||
                                usage.orphanBookDirCount > 0,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.st_data_clear),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { bucket ->
        AppBottomSheet(
            title = stringResource(bucketTitle(bucket)),
            description = stringResource(R.string.st_data_confirm_desc),
            onClose = { confirm = null },
            doneLabel = stringResource(R.string.st_data_clear),
            doneDanger = true,
            onDone = {
                confirm = null
                cleaning = bucket
                scope.launch {
                    runCatching { storage.clear(bucket) }
                    usage = runCatching { storage.refresh() }.getOrDefault(usage)
                    cleaning = null
                }
            },
        ) {
            Text(
                text = stringResource(bucketWarning(bucket)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (outOfLibraryConfirm) {
        AppBottomSheet(
            title = stringResource(R.string.st_data_out_of_library),
            description = stringResource(R.string.st_data_out_of_library_confirm_desc),
            onClose = { outOfLibraryConfirm = false },
            doneLabel = stringResource(R.string.st_data_clear),
            doneDanger = true,
            onDone = {
                outOfLibraryConfirm = false
                purgingOutOfLibrary = true
                scope.launch {
                    val result = runCatching { storage.purgeOutOfLibrary() }
                    if (result.isFailure) {
                        Toast.makeText(
                            context,
                            R.string.st_data_clear_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    usage = runCatching { storage.refresh() }.getOrDefault(usage)
                    purgingOutOfLibrary = false
                }
            },
        ) {
            Text(
                text = stringResource(R.string.st_data_out_of_library_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun bucketTitle(bucket: StorageBucket): Int = when (bucket) {
    StorageBucket.Database -> R.string.st_data_database
    StorageBucket.Images -> R.string.st_data_images
    StorageBucket.NovelCache -> R.string.st_data_novels
    StorageBucket.MangaCache -> R.string.st_data_manga
    StorageBucket.OfflineVideo -> R.string.st_data_video
}

private fun bucketWarning(bucket: StorageBucket): Int = when (bucket) {
    StorageBucket.Database -> R.string.st_data_database_warning
    StorageBucket.Images -> R.string.st_data_images_warning
    StorageBucket.NovelCache -> R.string.st_data_novels_warning
    StorageBucket.MangaCache -> R.string.st_data_manga_warning
    StorageBucket.OfflineVideo -> R.string.st_data_video_warning
}
