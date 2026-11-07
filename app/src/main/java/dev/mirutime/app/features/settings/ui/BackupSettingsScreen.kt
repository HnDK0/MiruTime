package dev.mirutime.app.features.settings.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.ChoiceSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsHint
import dev.mirutime.uikit.SettingsInfoRow
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.app.features.settings.backup.AutoBackupScheduler
import dev.mirutime.app.features.settings.backup.AutoBackupStatus
import dev.mirutime.app.features.settings.backup.BackupNotifications
import dev.mirutime.app.features.settings.backup.BackupOperationKind
import dev.mirutime.app.features.settings.backup.BackupOperationMonitor
import dev.mirutime.app.features.settings.backup.BackupOperationService
import dev.mirutime.app.features.settings.backup.BackupOperationState
import dev.mirutime.app.features.settings.backup.BackupRotation
import dev.mirutime.app.features.settings.backup.BackupSchedule
import dev.mirutime.app.features.settings.backup.SafBackupTree
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.BackupOptions

// Backup & restore. The screen only starts the foreground service (manual) or
// WorkManager (auto); it never runs backup code in a Compose scope. Progress
// comes from BackupOperationMonitor, while notifications stay the durable
// surface after the screen is gone.
@Composable
fun BackupSettingsScreen(contentPadding: PaddingValues) {
    val settings = AppSettings
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = LocalMiruExtraColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val operation by BackupOperationMonitor.state.collectAsState()

    var intervalSheet by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingOperation by remember { mutableStateOf<PendingOperation?>(null) }
    var pendingEnable by remember { mutableStateOf(false) }
    var openSettingsIfDenied by remember { mutableStateOf(false) }
    var notificationsAllowed by remember {
        mutableStateOf(BackupNotifications.canPostNotifications(context))
    }
    var refreshKey by remember { mutableIntStateOf(0) }

    fun toast(res: Int) {
        Toast.makeText(context, resources.getString(res), Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = BackupNotifications.canPostNotifications(context)
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folderUri = settings.backupDirectory
    val folderSelected = SafBackupTree.isTreeUri(folderUri)
    val permissionGranted = remember(folderUri, refreshKey) {
        SafBackupTree.hasPersistedPermission(context, folderUri)
    }
    val folderReady = folderSelected && permissionGranted
    val accessLost = folderSelected && !permissionGranted
    val folderName = remember(folderUri, refreshKey, resources) {
        if (!folderSelected) {
            null
        } else {
            SafBackupTree.displayName(context, folderUri)
                ?: resources.getString(R.string.st_backup_folder_selected)
        }
    }
    val autoAccess = if (accessLost) {
        AutoBackupAccess.PermissionLost
    } else {
        BackupUiModel.accessStatus(
            autoBackup = settings.autoBackup,
            folderSelected = folderSelected,
            permissionGranted = permissionGranted,
        )
    }

    LaunchedEffect(settings.backupIntervalMinutes) {
        // The scheduler floors the interval at 60 minutes; showing a smaller
        // value would promise a cadence the worker never uses.
        if (settings.backupIntervalMinutes < BackupSchedule.MinIntervalMinutes) {
            settings.backupIntervalMinutes = BackupSchedule.MinIntervalMinutes.toInt()
        }
    }
    LaunchedEffect(settings.autoBackup, folderReady, refreshKey) {
        if (settings.autoBackup && !folderReady) {
            // Backend policy owns the disable + notification, so a revoked SAF
            // grant cannot leave a periodic job retrying forever.
            AutoBackupScheduler.sync(context, AppSettings.persistedSnapshot())
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted || BackupNotifications.canPostNotifications(context)
        val queued = pendingOperation
        pendingOperation = null
        if (queued != null) {
            startOperation(context, queued)
        } else if (!notificationsAllowed && openSettingsIfDenied) {
            openNotificationSettings(context)
        }
        openSettingsIfDenied = false
    }

    fun beginOperation(pending: PendingOperation) {
        if (BackupNotifications.canPostNotifications(context)) {
            openSettingsIfDenied = false
            startOperation(context, pending)
        } else {
            pendingOperation = pending
            openSettingsIfDenied = false
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) beginOperation(PendingOperation.Backup(uri)) }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) restoreUri = uri }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            pendingEnable = false
            return@rememberLauncherForActivityResult
        }
        val stored = runCatching {
            SafBackupTree.persistPermission(context, uri)
            AutoBackupScheduler.setBackupDirectory(context, uri)
        }.isSuccess
        if (stored) {
            if (pendingEnable) AutoBackupScheduler.enable(context)
            refreshKey++
        } else {
            toast(R.string.st_backup_folder_pick_failed)
        }
        pendingEnable = false
    }

    fun chooseFolder(enableAfter: Boolean) {
        pendingEnable = enableAfter
        val initial = folderUri
            .takeIf { SafBackupTree.isTreeUri(it) }
            ?.let(Uri::parse)
        treePicker.launch(initial)
    }

    val backupBusy = operation.running && operation.kind == BackupOperationKind.Backup
    val restoreBusy = operation.running && operation.kind == BackupOperationKind.Restore
    val anyBusy = operation.running || pendingOperation != null

    val never = stringResource(R.string.st_backup_never)
    val lastManual = relativeTime(settings.backupLastTimestamp, never)
    val lastAuto = relativeTime(settings.autoBackupLastTimestamp, never)

    val statusRes = when (autoAccess) {
        AutoBackupAccess.Scheduled -> R.string.st_backup_status_scheduled
        AutoBackupAccess.PermissionLost -> R.string.st_backup_status_permission_lost
        AutoBackupAccess.NeedsFolder -> R.string.st_backup_status_needs_folder
        AutoBackupAccess.Off -> R.string.st_backup_status_off
    }
    val statusColor = when (autoAccess) {
        AutoBackupAccess.Scheduled -> extra.accent
        AutoBackupAccess.PermissionLost -> MaterialTheme.colorScheme.error
        AutoBackupAccess.NeedsFolder, AutoBackupAccess.Off -> extra.muted
    }

    SettingsPage(contentPadding) {
        item(key = "manual") {
            MiruSectionCard(
                title = stringResource(R.string.st_backup_section_manual),
                description = stringResource(R.string.st_backup_section_manual_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.st_backup_now),
                    subtitle = if (backupBusy) {
                        stringResource(R.string.st_backup_in_progress)
                    } else {
                        stringResource(R.string.st_backup_now_desc)
                    },
                    onClick = {
                        createArchive.launch(
                            BackupRotation.fileName(System.currentTimeMillis(), auto = false),
                        )
                    },
                    enabled = !anyBusy,
                    trailing = if (backupBusy) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(MiruSpace.Xs),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        null
                    },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Restore,
                    title = stringResource(R.string.st_backup_restore),
                    subtitle = if (restoreBusy) {
                        stringResource(R.string.st_backup_in_progress)
                    } else {
                        stringResource(R.string.st_backup_restore_desc)
                    },
                    onClick = {
                        // Providers report ZIP MIME types inconsistently; the
                        // restore validator, not the picker, decides validity.
                        openBackup.launch(arrayOf("*/*"))
                    },
                    enabled = !anyBusy,
                    trailing = if (restoreBusy) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(MiruSpace.Xs),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        null
                    },
                )
                if (operation.kind != null && (operation.running || operation.finished)) {
                    MiruRowDivider()
                    OperationStatusBlock(operation)
                }
                MiruRowDivider()
                SettingsInfoRow(
                    title = stringResource(R.string.st_backup_last_manual),
                    value = lastManual,
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.st_backup_notifications),
                    subtitle = stringResource(R.string.st_backup_notifications_desc),
                    value = stringResource(
                        if (notificationsAllowed) {
                            R.string.st_backup_notifications_on
                        } else {
                            R.string.st_backup_notifications_off
                        },
                    ),
                    valueColor = if (notificationsAllowed) extra.accent else extra.muted,
                    onClick = {
                        if (notificationsAllowed) {
                            openNotificationSettings(context)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            openSettingsIfDenied = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    },
                )
            }
        }
        item(key = "auto") {
            MiruSectionCard(
                title = stringResource(R.string.st_backup_auto),
                description = stringResource(R.string.st_backup_auto_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_backup_auto_switch),
                    subtitle = stringResource(R.string.st_backup_auto_switch_desc),
                    checked = settings.autoBackup,
                    onChange = { enabled ->
                        if (enabled) {
                            if (folderReady) {
                                AutoBackupScheduler.enable(context)
                                refreshKey++
                            } else {
                                chooseFolder(enableAfter = true)
                            }
                        } else {
                            AutoBackupScheduler.disable(context)
                            refreshKey++
                        }
                    },
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.st_backup_directory),
                    value = folderName ?: stringResource(R.string.st_backup_not_selected),
                    onClick = { chooseFolder(enableAfter = false) },
                )
                MiruRowDivider()
                SettingsValueRow(
                    title = stringResource(R.string.st_backup_interval),
                    value = backupIntervalLabel(settings.backupIntervalMinutes),
                    onClick = { intervalSheet = true },
                )
                MiruRowDivider()
                SettingsSliderRow(
                    title = stringResource(R.string.st_backup_max),
                    valueText = stringResource(R.string.st_backup_max_fmt, settings.backupMaxCount),
                    value = settings.backupMaxCount.toFloat(),
                    range = 1f..20f,
                    steps = 18,
                    onChange = { settings.backupMaxCount = it.toInt() },
                )
                MiruRowDivider()
                SettingsInfoRow(
                    title = stringResource(R.string.st_backup_status),
                    value = stringResource(statusRes),
                    valueColor = statusColor,
                )
                MiruRowDivider()
                SettingsInfoRow(
                    title = stringResource(R.string.st_backup_last_auto),
                    value = lastAuto,
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.st_backup_run_now),
                    subtitle = when (autoAccess) {
                        AutoBackupAccess.Scheduled -> {
                            stringResource(R.string.st_backup_run_now_desc)
                        }
                        AutoBackupAccess.Off -> {
                            stringResource(R.string.st_backup_run_now_off)
                        }
                        AutoBackupAccess.PermissionLost -> {
                            stringResource(R.string.st_backup_status_permission_lost)
                        }
                        AutoBackupAccess.NeedsFolder -> {
                            stringResource(R.string.st_backup_status_needs_folder)
                        }
                    },
                    onClick = {
                        when (
                            AutoBackupScheduler.runNow(
                                context,
                                AppSettings.persistedSnapshot(),
                            )
                        ) {
                            AutoBackupStatus.Scheduled -> toast(R.string.st_backup_run_now_queued)
                            AutoBackupStatus.PermissionLost -> {
                                refreshKey++
                                toast(R.string.st_backup_status_permission_lost)
                            }
                            AutoBackupStatus.Disabled -> toast(R.string.st_backup_run_now_off)
                        }
                    },
                    enabled = autoAccess == AutoBackupAccess.Scheduled && !operation.running,
                )
                when {
                    accessLost -> SettingsHint(stringResource(R.string.st_backup_access_lost_hint))
                    !folderSelected -> SettingsHint(stringResource(R.string.st_backup_folder_hint))
                    else -> SettingsHint(stringResource(R.string.st_backup_interval_hint))
                }
            }
        }
        item(key = "contents") {
            MiruSectionCard(
                title = stringResource(R.string.st_backup_section_contents),
                description = stringResource(R.string.st_backup_section_contents_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_backup_settings),
                    subtitle = stringResource(R.string.st_backup_settings_desc),
                    checked = settings.backupIncludeSettings,
                    onChange = { settings.backupIncludeSettings = it },
                )
                MiruRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.st_backup_images),
                    subtitle = stringResource(R.string.st_backup_images_desc),
                    checked = settings.backupIncludeImages,
                    onChange = { settings.backupIncludeImages = it },
                )
                MiruRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.st_backup_plugins),
                    subtitle = stringResource(R.string.st_backup_plugins_desc),
                    checked = settings.backupIncludePlugins,
                    onChange = { settings.backupIncludePlugins = it },
                )
                MiruRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.st_backup_history_only),
                    subtitle = stringResource(R.string.st_backup_history_only_desc),
                    checked = settings.backupIncludeHistoryOnly,
                    onChange = { settings.backupIncludeHistoryOnly = it },
                )
            }
        }
    }

    if (intervalSheet) {
        ChoiceSheet(
            title = stringResource(R.string.st_backup_interval),
            description = stringResource(R.string.st_backup_interval_desc),
            options = BackupUiModel.intervalOptions.map {
                ChoiceOption(it, backupIntervalLabel(it))
            },
            selected = settings.backupIntervalMinutes,
            onSelect = { settings.backupIntervalMinutes = it },
            onClose = { intervalSheet = false },
        )
    }

    restoreUri?.let { uri ->
        AppBottomSheet(
            title = stringResource(R.string.st_backup_restore_confirm_title),
            description = stringResource(R.string.st_backup_restore_confirm_desc),
            onClose = { restoreUri = null },
            doneLabel = stringResource(R.string.st_backup_restore_confirm),
            doneDanger = true,
            doneEnabled = !anyBusy,
            onDone = {
                restoreUri = null
                beginOperation(PendingOperation.Restore(uri))
            },
        ) {
            Text(
                text = stringResource(R.string.st_backup_restore_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private sealed interface PendingOperation {
    data class Backup(val uri: Uri) : PendingOperation
    data class Restore(val uri: Uri) : PendingOperation
}

private fun startOperation(context: Context, pending: PendingOperation) {
    // The service owns the work; resetting here hides a previous result while
    // the start intent is in flight. The service publishes "running" next.
    BackupOperationMonitor.clear()
    runCatching {
        when (pending) {
            is PendingOperation.Backup -> BackupOperationService.startBackup(
                context,
                pending.uri,
                BackupOptions.fromSettings(),
            )
            is PendingOperation.Restore -> BackupOperationService.startRestore(
                context,
                pending.uri,
            )
        }
    }.onFailure {
        val message = context.getString(
            if (pending is PendingOperation.Restore) {
                R.string.st_backup_restore_failed_title
            } else {
                R.string.st_backup_failed
            },
        )
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun OperationStatusBlock(state: BackupOperationState) {
    val kind = state.kind ?: return
    val extra = LocalMiruExtraColors.current
    val stage = stringResource(BackupUiModel.stageLabelRes(kind, state.stage))
    val text = when {
        state.running && kind == BackupOperationKind.Restore && state.total > 0 -> {
            stringResource(
                R.string.st_backup_progress_count_fmt,
                stage,
                state.current,
                state.total,
                state.percent,
            )
        }
        state.running -> stringResource(
            R.string.st_backup_progress_stage_fmt,
            stage,
            state.percent,
        )
        state.success == true -> stringResource(
            if (kind == BackupOperationKind.Restore) {
                R.string.st_backup_restore_done_title
            } else {
                R.string.st_backup_done
            },
        )
        else -> stringResource(
            if (kind == BackupOperationKind.Restore) {
                R.string.st_backup_restore_failed_title
            } else {
                R.string.st_backup_failed
            },
        )
    }
    val color: Color = when {
        state.running || state.success == true -> extra.accent
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        if (state.running) {
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                color = extra.accent,
                trackColor = extra.accent.copy(alpha = 0.16f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.Xs)
                    .semantics { contentDescription = text },
            )
        }
        val detail = state.message?.takeIf { it.isNotBlank() && it != text }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(top = MiruSpace.Xxs),
            )
        }
    }
}

@Composable
private fun backupIntervalLabel(minutes: Int): String = when (minutes) {
    60 -> stringResource(R.string.st_interval_1h)
    360 -> stringResource(R.string.st_interval_6h)
    720 -> stringResource(R.string.st_interval_12h)
    1440 -> stringResource(R.string.st_interval_24h)
    10080 -> stringResource(R.string.st_interval_week)
    else -> stringResource(R.string.st_interval_minutes_fmt, minutes)
}

private fun relativeTime(timestamp: Long, never: String): String =
    if (timestamp <= 0L) {
        never
    } else {
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
