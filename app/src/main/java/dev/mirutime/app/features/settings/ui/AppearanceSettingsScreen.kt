package dev.mirutime.app.features.settings.ui

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.ThemeMode
import dev.mirutime.uikit.ChoiceSheet
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.app.design.components.ThemePickerRails
import dev.mirutime.app.features.settings.data.AppSettings
import kotlin.math.roundToInt

// Appearance: app language + the theme picker rails (classic themes, ready
// made backgrounds from GitHub, the user's own backgrounds). The theme
// drives the real app theme; the language is applied by MainActivity's
// locale-wrapped base context and a recreate, so visible strings switch now.
@Composable
fun AppearanceSettingsScreen(
    contentPadding: PaddingValues,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    customBg: String?,
    bgLibrary: List<String>,
    onCustomBg: (String?) -> Unit,
    onRemoveBg: (String) -> Unit,
) {
    var languageSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SettingsPage(contentPadding) {
        item(key = "language") {
            MiruSectionCard(
                title = stringResource(R.string.st_language),
                description = stringResource(R.string.st_language_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_language_app),
                    value = appLanguageLabel(AppSettings.appLanguage),
                    onClick = { languageSheet = true },
                )
            }
        }
        item(key = "scale") {
            MiruSectionCard(
                title = stringResource(R.string.st_scale),
                description = stringResource(R.string.st_scale_desc),
                contentPadding = PaddingValues(MiruSpace.L),
            ) {
                ScaleSlider(
                    label = stringResource(R.string.st_text_scale),
                    value = AppSettings.textScale,
                    contentDescriptionRes = R.string.st_scale_a11y_fmt,
                    onChange = { AppSettings.textScale = it },
                )
                Spacer(modifier = Modifier.height(MiruSpace.L))
                ScaleSlider(
                    label = stringResource(R.string.st_ui_scale),
                    value = AppSettings.uiScale,
                    contentDescriptionRes = R.string.st_ui_scale_a11y_fmt,
                    onChange = { AppSettings.uiScale = it },
                )
            }
        }
        item(key = "theme") {
            MiruSectionCard(
                title = stringResource(R.string.st_theme),
                description = stringResource(R.string.settings_appearance_desc),
                contentPadding = PaddingValues(MiruSpace.L),
            ) {
                ThemePickerRails(
                    selected = themeMode,
                    customBg = customBg,
                    bgLibrary = bgLibrary,
                    onSelect = onThemeMode,
                    onCustomBg = onCustomBg,
                    onRemoveBg = onRemoveBg,
                    modifier = Modifier.padding(0.dp),
                )
            }
        }
    }

    if (languageSheet) {
        ChoiceSheet(
            title = stringResource(R.string.st_language_app),
            description = stringResource(R.string.st_language_sheet_desc),
            options = appLanguageOptions(),
            selected = AppSettings.appLanguage,
            onSelect = { code ->
                val changed = AppSettings.appLanguage != code
                AppSettings.appLanguage = code
                languageSheet = false
                // The locale is read in attachBaseContext, so only a recreate
                // can rebuild the already-composed resources.
                if (changed) (context as? Activity)?.recreate()
            },
            onClose = { languageSheet = false },
        )
    }
}

@Composable
private fun ScaleSlider(
    label: String,
    value: Int,
    @StringRes contentDescriptionRes: Int,
    onChange: (Int) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    // Moving the slider must not change LocalDensity mid-gesture: Compose
    // resets every pointerInput on a density change, so the thumb would stop
    // after the first 5% step. The draft is applied once, on release.
    var draft by remember { mutableStateOf<Int?>(null) }
    val shown = draft ?: value
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.st_scale_value_fmt, shown),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
        }
        MiruSlider(
            value = shown.toFloat(),
            onValueChange = {
                draft = ((it / 5f).roundToInt() * 5).coerceIn(50, 150)
            },
            valueRange = 50f..150f,
            steps = 0,
            contentDescription = stringResource(contentDescriptionRes, shown),
            onValueChangeFinished = {
                draft?.let(onChange)
                draft = null
            },
        )
    }
}
