package dev.mirutime.app.features.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.NovelTranslation
import dev.mirutime.app.features.settings.ui.TranslationConfigContent
import dev.mirutime.app.features.settings.ui.isTranslationSaveEnabled
import dev.mirutime.app.features.translation.data.pluginIdOf
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SheetSection

/**
 * App-owned translation sheet content: the reader feature exposes the panel as
 * a slot, so per-book settings stay next to the settings screens that own them.
 */
@Composable
fun AppReaderTranslationPanel(
    bookUrl: String,
    onDismiss: () -> Unit,
) {
    val s = AppSettings
    var config by remember(bookUrl) {
        mutableStateOf(s.novelTranslation[bookUrl] ?: NovelTranslation())
    }
    val plugin = s.pluginTranslation[dev.mirutime.app.features.translation.data.translationSourceId(bookUrl)]
    val level = when {
        config.enabled -> R.string.rd_level_novel
        plugin?.enabled == true -> R.string.rd_level_plugin
        s.translationGlobalMode -> R.string.rd_level_global
        else -> R.string.rd_level_none
    }

    AppBottomSheet(
        title = stringResource(R.string.rd_translation),
        description = stringResource(R.string.rd_translation_desc),
        onClose = onDismiss,
        doneLabel = stringResource(R.string.st_save),
        doneEnabled = isTranslationSaveEnabled(config),
        onDone = {
            s.novelTranslation[bookUrl] = config
            onDismiss()
        },
    ) {
        // Section-style status line: same small muted label as every section
        // header, so the sheet reads as one rhythm before the editor starts.
        Text(
            text = stringResource(
                R.string.rd_translation_level_fmt,
                stringResource(level),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.muted,
            modifier = Modifier.padding(vertical = MiruSpace.S),
        )
        MiruRowDivider()
        TranslationConfigContent(
            config = config,
            showScope = false,
            afterPair = { ParallelTranslationSection() },
        ) { config = it }
    }
}

// The reader keeps the global display mode here, next to the book's own
// translation settings. It applies immediately; Save stores only the book.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParallelTranslationSection() {
    val s = AppSettings
    SheetSection(title = stringResource(R.string.st_tr_parallel_title)) {
        Text(
            text = stringResource(R.string.st_tr_parallel_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MiruSpace.S),
        ) {
            FilterChip(
                selected = !s.parallelEnabled,
                onClick = { s.parallelEnabled = false },
                label = { Text(stringResource(R.string.st_off)) },
            )
            FilterChip(
                selected = s.parallelEnabled && s.parallelOrder == "TRANSLATION_FIRST",
                onClick = {
                    s.parallelEnabled = true
                    s.parallelOrder = "TRANSLATION_FIRST"
                },
                label = { Text(stringResource(R.string.st_tr_order_translation)) },
            )
            FilterChip(
                selected = s.parallelEnabled && s.parallelOrder == "ORIGINAL_FIRST",
                onClick = {
                    s.parallelEnabled = true
                    s.parallelOrder = "ORIGINAL_FIRST"
                },
                label = { Text(stringResource(R.string.st_tr_order_original)) },
            )
        }
        Text(
            text = stringResource(R.string.st_tr_parallel_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
    }
}
