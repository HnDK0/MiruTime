package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.app.di.ReaderGraph
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.ui.ReaderReadingControls
import dev.mirutime.feature.reader.ui.ReaderStyleControls

// Reader: typography, layout, colors, reading switches and reset. Changes go
// straight to the same config the reader renders; the reader's Style sheet
// keeps only visual style, while reading switches live in More.
@Composable
fun ReaderSettingsScreen(
    contentPadding: PaddingValues,
    readerGraph: ReaderGraph,
) {
    val config by readerGraph.config.config.collectAsState()
    SettingsPage(contentPadding) {
        item(key = "reader-style") {
            ReaderStyleControls(
                style = config.style,
                onStyleChange = readerGraph.config::setStyle,
                onReset = { readerGraph.config.setStyle(ReaderStyle.Default) },
                assets = readerGraph.assets,
            )
        }
        item(key = "reader-reading") {
            ReaderReadingControls(
                style = config.style,
                onStyleChange = readerGraph.config::setStyle,
            )
        }
    }
}
