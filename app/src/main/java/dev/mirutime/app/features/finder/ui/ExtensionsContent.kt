package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import dev.mirutime.app.R
import dev.mirutime.app.design.components.PluginAvatar
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.uikit.MiruTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

// Extensions tab: the repository card (URL + refresh +
// Lua import), the installed plugins (enable switch, per-row Update, settings
// on tap, "Update all" in the card header) and the plugins the repo offers.
// Pure UI: every change is a callback, the data layer applies it.
fun LazyListScope.extensionsItems(
    plugins: List<PluginExtension>?,
    busyIds: Set<String>,
    repoUrl: String,
    repoBusy: Boolean,
    onRefreshRepo: (String) -> Unit,
    onLuaImport: () -> Unit,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenSettings: (PluginExtension) -> Unit,
) {
    item(key = "repo") {
        var url by remember(repoUrl) { mutableStateOf(repoUrl) }
        MiruSectionCard(
            title = stringResource(R.string.finder_repo_title),
            description = stringResource(R.string.finder_repo_desc),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(MiruSpace.L)) {
                MiruTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    plate = false,
                )
                Spacer(modifier = Modifier.height(MiruSpace.M))
                Button(
                    onClick = { onRefreshRepo(url) },
                    enabled = !repoBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MiruSize.Button),
                ) {
                    if (repoBusy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(text = stringResource(R.string.finder_repo_refresh), maxLines = 1)
                    }
                }
            }
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.finder_lua_title),
                subtitle = stringResource(R.string.finder_lua_sub),
                onClick = onLuaImport,
            )
        }
    }

    val installed = plugins.orEmpty().filter { it.installed }
        .sortedBy { it.name.lowercase() }
    val available = plugins.orEmpty().filter { !it.installed }
        .sortedBy { it.name.lowercase() }
    val updates = installed.count { it.updateAvailable }

    item(key = "installed") {
        MiruSectionCard(
            title = stringResource(R.string.finder_installed_title_fmt, installed.size),
            description = if (updates > 0) {
                stringResource(R.string.finder_updates_available_fmt, updates)
            } else {
                stringResource(R.string.finder_all_up_to_date)
            },
            actionLabel = if (updates > 0) stringResource(R.string.finder_update_all) else null,
            onAction = if (updates > 0) onUpdateAll else null,
            contentPadding = PaddingValues(0.dp),
        ) {
            when {
                plugins == null -> Text(
                    text = stringResource(R.string.finder_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(MiruSpace.L),
                )
                installed.isEmpty() -> Text(
                    text = stringResource(R.string.finder_installed_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(MiruSpace.L),
                )
                else -> installed.forEachIndexed { index, plugin ->
                    if (index > 0) MiruRowDivider()
                    ExtensionRow(
                        plugin = plugin,
                        busy = plugin.id in busyIds,
                        onClick = { onOpenSettings(plugin) },
                        onUpdate = { onUpdate(plugin.id) },
                        onToggle = { onToggle(plugin.id, it) },
                    )
                }
            }
        }
    }

    item(key = "available") {
        MiruSectionCard(
            title = stringResource(R.string.finder_available_title_fmt, available.size),
            description = stringResource(R.string.finder_available_desc),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (available.isEmpty()) {
                Text(
                    text = stringResource(R.string.finder_available_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(MiruSpace.L),
                )
            } else {
                available.forEachIndexed { index, plugin ->
                    if (index > 0) MiruRowDivider()
                    AvailableRow(
                        plugin = plugin,
                        busy = plugin.id in busyIds,
                        onInstall = { onInstall(plugin.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    plugin: PluginExtension,
    busy: Boolean,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.M, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        PluginAvatar(name = plugin.name, iconUrl = plugin.iconUrl)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (plugin.updateAvailable) {
                Text(
                    text = stringResource(
                        R.string.finder_version_update_fmt,
                        plugin.installedVersion.orEmpty(),
                        plugin.availableVersion,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.accent,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.finder_source_meta_fmt,
                        plugin.language,
                        typeLabel(plugin.type),
                        plugin.installedVersion.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            busy -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = extra.accent,
                modifier = Modifier.size(22.dp),
            )
            plugin.updateAvailable -> TextButton(onClick = onUpdate) {
                Text(
                    text = stringResource(R.string.finder_update),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        Switch(checked = plugin.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun AvailableRow(
    plugin: PluginExtension,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.S, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        PluginAvatar(name = plugin.name, iconUrl = plugin.iconUrl)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.finder_source_meta_fmt,
                    plugin.language,
                    typeLabel(plugin.type),
                    plugin.availableVersion,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = extra.accent,
                modifier = Modifier
                    .padding(end = MiruSpace.M)
                    .size(22.dp),
            )
        } else {
            TextButton(onClick = onInstall) {
                Text(
                    text = stringResource(R.string.finder_install),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}
