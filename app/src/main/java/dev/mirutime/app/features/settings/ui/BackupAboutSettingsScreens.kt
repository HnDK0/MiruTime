package dev.mirutime.app.features.settings.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.mirutime.app.BuildConfig
import dev.mirutime.app.R
import dev.mirutime.core.log.LogExporter
import dev.mirutime.core.log.MiruFileLogger
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.app.features.updates.data.AppRelease
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG_FILE_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

// About + support: version (tap copies it), the repository, a bug report
// shortcut and the log export used for troubleshooting.
@Composable
fun AboutSettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val version = remember {
        val name = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        val hash = runCatching { BuildConfig.GIT_COMMIT }.getOrDefault("dev")
        "$name · ${BuildConfig.BUILD_TYPE} · $hash"
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    val scope = rememberCoroutineScope()
    var exportingLogs by remember { mutableStateOf(false) }
    val exportLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportingLogs = true
            val written = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = MiruFileLogger.currentDirectory()
                            ?: return@runCatching 0L
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Cannot open the selected file")
                        output.use { LogExporter.write(dir, it) }
                    }
                }
            } finally {
                exportingLogs = false
            }
            when {
                written.isFailure -> toast(resources.getString(R.string.st_support_logs_failed))
                (written.getOrNull() ?: 0L) <= 0L ->
                    toast(resources.getString(R.string.st_support_logs_empty))
                else -> toast(resources.getString(R.string.st_support_logs_exported))
            }
        }
    }

    SettingsPage(contentPadding) {
        item(key = "about") {
            MiruSectionCard(
                title = stringResource(R.string.st_about),
                description = stringResource(R.string.st_about_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.st_about_version),
                    subtitle = version,
                    onClick = {
                        clipboard.setText(AnnotatedString(version))
                        Toast.makeText(
                            context,
                            resources.getString(R.string.st_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Code,
                    title = stringResource(R.string.st_about_repo),
                    subtitle = AppRelease.RepoUrl.removePrefix("https://"),
                    onClick = { open(AppRelease.RepoUrl) },
                )
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.st_about_report),
                    subtitle = stringResource(R.string.st_about_report_desc),
                    onClick = { open("$AppRelease.RepoUrl/issues/new") },
                )
            }
        }
        item(key = "support") {
            MiruSectionCard(
                title = stringResource(R.string.st_support),
                description = stringResource(R.string.st_support_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                MiruActionRow(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.st_support_logs),
                    subtitle = if (exportingLogs) {
                        stringResource(R.string.loading_exporting)
                    } else {
                        stringResource(R.string.st_support_logs_desc)
                    },
                    enabled = !exportingLogs,
                    trailing = if (exportingLogs) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = LocalMiruExtraColors.current.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        val dir = MiruFileLogger.currentDirectory()
                        if (dir == null || LogExporter.files(dir).isEmpty()) {
                            toast(resources.getString(R.string.st_support_logs_empty))
                        } else {
                            exportLogs.launch(
                                "mirutime-logs-" + LOG_FILE_TIMESTAMP.format(Instant.now()) + ".txt",
                            )
                        }
                    },
                )
            }
        }
    }
}
