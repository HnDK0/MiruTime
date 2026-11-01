package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.StorageBucket

// Settings home: NOT a wall of switches. Every
// section is its own screen; this page lists them in three groups, each row
// showing a live one-line summary of what is set inside. Nothing is edited
// here.
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpen: (route: String) -> Unit,
    readerStyle: ReaderStyle = ReaderStyle.Default,
) {
    val settings = AppSettings
    val perNovel = !settings.translationGlobalMode
    val context = LocalContext.current
    val storage = remember(context) {
        (context.applicationContext as dev.mirutime.app.MiruApp).storageUsageService
    }
    var storageTotal by remember { mutableStateOf(0L) }
    // "…" until storage.refresh() returns: a 0 B summary would be a lie.
    var storageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(storage) {
        storageTotal = runCatching { storage.refresh().totalBytes }.getOrDefault(0L)
        storageLoaded = true
    }
    val storageSummary = if (storageLoaded) {
        formatBytes(storageTotal)
    } else {
        stringResource(R.string.loading_value)
    }

    SettingsPage(contentPadding) {
        item(key = "app") {
            MiruSectionCard(
                title = stringResource(R.string.st_group_app),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.st_appearance),
                    subtitle = appLanguageLabel(settings.appLanguage) + " · " +
                        stringResource(R.string.st_appearance_summary),
                    onClick = { onOpen(SettingsRoutes.Appearance) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.LibraryBooks,
                    title = stringResource(R.string.st_library),
                    subtitle = stringResource(R.string.st_library_summary_fmt, settings.gridColumns) +
                        " · " + stringResource(
                        if (settings.listLayout == "grid") R.string.st_layout_grid else R.string.st_layout_list,
                    ),
                    onClick = { onOpen(SettingsRoutes.Library) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.st_updates),
                    subtitle = if (settings.libraryAutoUpdate) {
                        stringResource(R.string.st_updates_summary_fmt, intervalLabel(settings.libraryIntervalHours))
                    } else {
                        stringResource(R.string.st_updates_summary_off)
                    },
                    onClick = { onOpen(SettingsRoutes.Updates) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Download,
                    title = stringResource(R.string.st_downloads),
                    subtitle = stringResource(R.string.st_downloads_summary),
                    onClick = { onOpen(SettingsRoutes.Downloads) },
                )
            }
        }
        item(key = "reading") {
            MiruSectionCard(
                title = stringResource(R.string.st_group_reading),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.MenuBook,
                    title = stringResource(R.string.st_reader),
                    subtitle = stringResource(
                        R.string.st_reader_summary_fmt,
                        readerStyle.fontSizeSp,
                        (readerStyle.lineHeight * 100).toInt(),
                    ),
                    onClick = { onOpen(SettingsRoutes.Reader) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.st_translation),
                    subtitle = providerLabel(settings.provider) + " · " + stringResource(
                        if (perNovel) R.string.st_mode_per_novel else R.string.st_mode_global,
                    ),
                    onClick = { onOpen(SettingsRoutes.Translation) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.FindReplace,
                    title = stringResource(R.string.st_regex),
                    subtitle = stringResource(
                        R.string.st_regex_summary_fmt,
                        settings.globalRegexRules.count { it.enabled },
                        settings.globalRegexRules.size,
                    ),
                    onClick = { onOpen(SettingsRoutes.Regex) },
                )
            }
        }
        item(key = "system") {
            MiruSectionCard(
                title = stringResource(R.string.st_group_system),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.Public,
                    title = stringResource(R.string.st_network),
                    subtitle = stringResource(
                        if (settings.cloudflareBypass) R.string.st_network_summary_on else R.string.st_network_summary_off,
                    ),
                    onClick = { onOpen(SettingsRoutes.Network) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.st_data),
                    subtitle = stringResource(R.string.st_data_summary_fmt, storageSummary),
                    onClick = { onOpen(SettingsRoutes.Data) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.st_backup),
                    subtitle = stringResource(
                        if (settings.autoBackup) R.string.st_backup_summary_on else R.string.st_backup_summary_off,
                    ),
                    onClick = { onOpen(SettingsRoutes.Backup) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.st_about),
                    subtitle = stringResource(R.string.st_about_summary),
                    onClick = { onOpen(SettingsRoutes.About) },
                )
            }
        }
    }
}
