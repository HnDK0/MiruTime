package dev.mirutime.feature.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SearchableChoiceSheet
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import dev.mirutime.feature.reader.api.ReaderAssetPort
import dev.mirutime.feature.reader.Autoscroll
import dev.mirutime.feature.reader.ReaderPalette
import dev.mirutime.feature.reader.ReaderStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Shared Style sections: the Settings → Reader page and the reader Style
// sheet edit the same ReaderStyle through the same controls. On the settings
// page they sit in cards; hosted in a sheet they become flat SheetSections so
// the sheet never nests a shadowed card inside its own surface.
@Composable
fun ReaderStyleControls(
    style: ReaderStyle,
    onStyleChange: (ReaderStyle) -> Unit,
    onReset: () -> Unit,
    assets: ReaderAssetPort,
    modifier: Modifier = Modifier,
    hostedInSheet: Boolean = false,
    /** Keep the old asset until Save; Cancel deletes only the new preview asset. */
    deferAssetDeletion: Boolean = false,
) {
    var fontSheet by remember { mutableStateOf(false) }
    var fontOptions by remember { mutableStateOf(ReaderFontCatalog.legacyOptions()) }
    var resetConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(fontSheet) {
        if (fontSheet) fontOptions = ReaderFontCatalog.load()
    }
    val update: (ReaderStyle) -> Unit = { onStyleChange(it.sanitized()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentStyle by rememberUpdatedState(style)
    val currentUpdate by rememberUpdatedState(update)
    val backgroundImportFailed = stringResource(R.string.rd_background_import_failed)
    val fontImportFailed = stringResource(R.string.rd_font_import_failed)

    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val asset = assets.importBackground(
                    uri,
                    previousPath = if (deferAssetDeletion) null else currentStyle.backgroundImagePath,
                )
                if (asset != null) {
                    currentUpdate(
                        currentStyle.copy(
                            backgroundImagePath = asset.path,
                            backgroundImageName = asset.displayName,
                        ),
                    )
                } else {
                    Toast.makeText(context, backgroundImportFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val asset = assets.importFont(
                    uri,
                    previousPath = if (deferAssetDeletion) null else currentStyle.customFontPath,
                )
                if (asset != null) {
                    currentUpdate(
                        currentStyle.copy(
                            customFontPath = asset.path,
                            customFontName = asset.displayName,
                        ),
                    )
                } else {
                    Toast.makeText(context, fontImportFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier,
        // SheetSections carry their own vertical rhythm, so the sheet drops
        // the card gap; the settings page keeps the standard gap.
        verticalArrangement = Arrangement.spacedBy(if (hostedInSheet) 0.dp else MiruSize.Gap),
    ) {
        val rowPad = if (hostedInSheet) 0.dp else MiruSpace.L
        ReaderStyleSection(
            title = stringResource(R.string.rd_typography),
            inSheet = hostedInSheet,
        ) {
            SettingsValueRow(
                title = stringResource(R.string.rd_font),
                value = fontLabel(style.fontFamily),
                onClick = { fontSheet = true },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsValueRow(
                title = stringResource(R.string.rd_custom_font),
                subtitle = if (style.customFontPath != null) {
                    stringResource(R.string.rd_custom_font_active)
                } else {
                    null
                },
                value = style.customFontName ?: stringResource(R.string.rd_custom_asset_none),
                onClick = { fontPicker.launch(FontMimeTypes) },
                horizontalPadding = rowPad,
                trailing = if (style.customFontPath != null) {
                    {
                        RemoveAssetButton(
                            contentDescription = stringResource(R.string.rd_custom_font_remove),
                            onClick = {
                                if (!deferAssetDeletion) {
                                    assets.deleteFont(style.customFontPath)
                                }
                                update(
                                    style.copy(
                                        customFontPath = null,
                                        customFontName = null,
                                    ),
                                )
                            },
                        )
                    }
                } else {
                    null
                },
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_font_size),
                valueText = "${style.fontSizeSp} sp",
                value = style.fontSizeSp.toFloat(),
                range = 8f..32f,
                steps = 23,
                onChange = { update(style.copy(fontSizeSp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_line_spacing),
                valueText = String.format(Locale.US, "%.2f×", style.lineHeight),
                value = style.lineHeight,
                range = 1.0f..2.5f,
                steps = 14,
                onChange = { update(style.copy(lineHeight = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_paragraph_spacing),
                valueText = "${style.paragraphSpacingDp} dp",
                value = style.paragraphSpacingDp.toFloat(),
                range = 0f..40f,
                steps = 39,
                onChange = { update(style.copy(paragraphSpacingDp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_letter_spacing),
                valueText = String.format(Locale.US, "%.2f em", style.letterSpacingEm),
                value = style.letterSpacingEm,
                range = 0f..0.3f,
                steps = 29,
                onChange = { update(style.copy(letterSpacingEm = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_bold),
                checked = style.bold,
                onChange = { update(style.copy(bold = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_italic),
                checked = style.italic,
                onChange = { update(style.copy(italic = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_underline),
                checked = style.underline,
                onChange = { update(style.copy(underline = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_shadow),
                checked = style.shadow,
                onChange = { update(style.copy(shadow = it)) },
                horizontalPadding = rowPad,
            )
        }

        ReaderStyleSection(
            title = stringResource(R.string.rd_layout),
            inSheet = hostedInSheet,
        ) {
            SettingsSliderRow(
                title = stringResource(R.string.rd_margin_start),
                valueText = "${style.marginStartDp} dp",
                value = style.marginStartDp.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { update(style.copy(marginStartDp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_margin_end),
                valueText = "${style.marginEndDp} dp",
                value = style.marginEndDp.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { update(style.copy(marginEndDp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_margin_top),
                valueText = "${style.marginTopDp} dp",
                value = style.marginTopDp.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { update(style.copy(marginTopDp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_margin_bottom),
                valueText = "${style.marginBottomDp} dp",
                value = style.marginBottomDp.toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { update(style.copy(marginBottomDp = it.toInt())) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_justify),
                checked = style.justify,
                onChange = { update(style.copy(justify = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_hyphenation),
                checked = style.hyphenation,
                onChange = { update(style.copy(hyphenation = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_paragraph_indent),
                checked = style.paragraphIndent,
                onChange = { update(style.copy(paragraphIndent = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_first_letter),
                checked = style.firstLetterBold,
                onChange = { update(style.copy(firstLetterBold = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_split_sentences),
                checked = style.splitSentences,
                onChange = { update(style.copy(splitSentences = it)) },
                horizontalPadding = rowPad,
            )
        }

        ReaderStyleSection(
            title = stringResource(R.string.rd_colors),
            inSheet = hostedInSheet,
        ) {
            TextColorSection(
                selected = style.textColor,
                onSelect = { update(style.copy(textColor = it)) },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            BackgroundSection(
                selectedId = style.backgroundId,
                onSelect = { id ->
                    update(
                        style.copy(
                            backgroundId = id,
                            textColor = null,
                            backgroundImagePath = null,
                            backgroundImageName = null,
                        ),
                    )
                },
                horizontalPadding = rowPad,
            )
            MiruRowDivider()
            SettingsValueRow(
                title = stringResource(R.string.rd_custom_background),
                subtitle = if (style.backgroundImagePath != null) {
                    stringResource(R.string.rd_custom_background_active)
                } else {
                    null
                },
                value = style.backgroundImageName
                    ?: stringResource(R.string.rd_custom_asset_none),
                onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                horizontalPadding = rowPad,
                trailing = if (style.backgroundImagePath != null) {
                    {
                        RemoveAssetButton(
                            contentDescription = stringResource(
                                R.string.rd_custom_background_remove,
                            ),
                            onClick = {
                                if (!deferAssetDeletion) {
                                    assets.deleteBackground(style.backgroundImagePath)
                                }
                                update(
                                    style.copy(
                                        backgroundImagePath = null,
                                        backgroundImageName = null,
                                    ),
                                )
                            },
                        )
                    }
                } else {
                    null
                },
            )
        }

        // Inside a sheet Reset lives in the header (see ReaderStylePanel); the
        // Settings page keeps its row because it is not a sheet.
        if (!hostedInSheet) {
            ReaderStyleSection(
                title = stringResource(R.string.rd_reset),
                inSheet = false,
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.rd_reset_style),
                    value = "",
                    onClick = { resetConfirm = true },
                    horizontalPadding = rowPad,
                )
            }
        }
    }

    if (fontSheet) {
        SearchableChoiceSheet(
            title = stringResource(R.string.rd_font),
            description = stringResource(R.string.rd_typography),
            options = fontOptions.map { key ->
                ChoiceOption(key, fontLabel(key))
            },
            selected = ReaderFontCatalog.canonicalKey(style.fontFamily),
            onSelect = {
                update(
                    style.copy(
                        fontFamily = it,
                        customFontPath = null,
                        customFontName = null,
                    ),
                )
            },
            onClose = { fontSheet = false },
            searchPlaceholder = stringResource(R.string.rd_font_search),
            emptyLabel = stringResource(R.string.rd_font_search_empty),
        )
    }
    if (resetConfirm && !hostedInSheet) {
        AppBottomSheet(
            title = stringResource(R.string.rd_reset_confirm_title),
            description = stringResource(R.string.rd_reset_confirm_desc),
            onClose = { resetConfirm = false },
            doneLabel = stringResource(R.string.rd_reset_style),
            doneDanger = true,
            closeLabel = stringResource(R.string.lib_cancel),
            onDone = {
                resetConfirm = false
                onReset()
            },
            content = {},
        )
    }
}

// Reading controls belong to the reader More sheet and Settings → Reader, not
// to the reader Style sheet. Both hosts share this one implementation.
@Composable
fun ReaderReadingControls(
    style: ReaderStyle,
    onStyleChange: (ReaderStyle) -> Unit,
    hostedInSheet: Boolean = false,
    autoScrollEnabled: Boolean = false,
    autoScrollSupported: Boolean = true,
    autoScrollSubtitle: String? = null,
    onAutoScrollChange: ((Boolean) -> Unit)? = null,
) {
    val update: (ReaderStyle) -> Unit = { onStyleChange(it.sanitized()) }
    val rowPad = if (hostedInSheet) 0.dp else MiruSpace.L
    ReaderStyleSection(
        title = stringResource(R.string.rd_reading),
        inSheet = hostedInSheet,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.rd_keep_screen_on),
            checked = style.keepScreenOn,
            onChange = { update(style.copy(keepScreenOn = it)) },
            horizontalPadding = rowPad,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.rd_fullscreen),
            subtitle = stringResource(R.string.rd_fullscreen_desc),
            checked = style.fullscreen,
            onChange = { update(style.copy(fullscreen = it)) },
            horizontalPadding = rowPad,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.rd_selectable_text),
            subtitle = stringResource(R.string.rd_selectable_text_desc),
            checked = style.selectableText,
            onChange = { update(style.copy(selectableText = it)) },
            horizontalPadding = rowPad,
        )
        MiruRowDivider()
        // The same gesture choice as the reader's More sheet, now also in
        // Settings → Reader.
        Text(
            text = stringResource(R.string.rd_menu_opens_with),
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.muted,
            modifier = Modifier.padding(start = rowPad, top = MiruSpace.S),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            modifier = Modifier.padding(horizontal = rowPad, vertical = MiruSpace.S),
        ) {
            MiruFilterChip(
                selected = !style.doubleTapMenu,
                onClick = { update(style.copy(doubleTapMenu = false)) },
                label = stringResource(R.string.rd_tap_one),
            )
            MiruFilterChip(
                selected = style.doubleTapMenu,
                onClick = { update(style.copy(doubleTapMenu = true)) },
                label = stringResource(R.string.rd_tap_two),
            )
        }
        if (onAutoScrollChange != null) {
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.rd_autoscroll),
                subtitle = autoScrollSubtitle,
                checked = autoScrollEnabled,
                enabled = autoScrollSupported,
                onChange = onAutoScrollChange,
                horizontalPadding = rowPad,
            )
        }
        if (autoScrollSupported) {
            MiruRowDivider()
            SettingsSliderRow(
                title = stringResource(R.string.rd_autoscroll_speed),
                valueText = stringResource(
                    R.string.rd_autoscroll_speed_value,
                    style.autoScrollSpeed,
                ),
                value = style.autoScrollSpeed.toFloat(),
                range = Autoscroll.MinSpeed.toFloat()..Autoscroll.MaxSpeed.toFloat(),
                steps = (Autoscroll.MaxSpeed - Autoscroll.MinSpeed) / Autoscroll.Step - 1,
                onChange = {
                    update(style.copy(autoScrollSpeed = it.roundToInt()))
                },
                horizontalPadding = rowPad,
            )
        }
    }
}

// One Style block in both hosts: a titled card on the Settings page, a flat
// collapsible SheetSection inside the reader Style sheet.
@Composable
private fun ReaderStyleSection(
    title: String,
    inSheet: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (inSheet) {
        SheetSection(title = title, content = content)
    } else {
        MiruSectionCard(
            title = title,
            contentPadding = PaddingValues(0.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextColorSection(
    selected: Long?,
    onSelect: (Long?) -> Unit,
    horizontalPadding: Dp,
) {
    Text(
        text = stringResource(R.string.rd_text_color),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(
            start = horizontalPadding,
            top = MiruSpace.M,
            end = horizontalPadding,
        ),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = MiruSpace.S),
    ) {
        SwatchDot(
            fill = MaterialTheme.colorScheme.surfaceContainerHighest,
            letterColor = MaterialTheme.colorScheme.onSurface,
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        ReaderPalette.textColors.forEach { color ->
            SwatchDot(
                fill = Color(color),
                letterColor = null,
                selected = selected == color,
                onClick = { onSelect(color) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSection(
    selectedId: String,
    onSelect: (String) -> Unit,
    horizontalPadding: Dp,
) {
    Text(
        text = stringResource(R.string.rd_background),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(
            start = horizontalPadding,
            top = MiruSpace.M,
            end = horizontalPadding,
        ),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = MiruSpace.S),
    ) {
        ReaderPalette.backgrounds.forEach { background ->
            val auto = background.id == "auto"
            SwatchDot(
                fill = if (auto) MaterialTheme.colorScheme.background else Color(background.backgroundArgb),
                letterColor = if (auto) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    Color(background.textArgb)
                },
                selected = selectedId == background.id,
                onClick = { onSelect(background.id) },
            )
        }
    }
}

@Composable
private fun SwatchDot(
    fill: Color,
    letterColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        if (letterColor != null) {
            Text(
                text = "A",
                style = MaterialTheme.typography.labelMedium,
                color = letterColor,
            )
        }
    }
}

@Composable
private fun RemoveAssetButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(MiruSize.Touch),
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = contentDescription,
            tint = LocalMiruExtraColors.current.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun fontLabel(key: String): String = when (ReaderFontCatalog.canonicalKey(key)) {
    "serif" -> stringResource(R.string.rd_font_serif)
    "sans-serif" -> stringResource(R.string.rd_font_sans)
    "monospace" -> stringResource(R.string.rd_font_mono)
    else -> ReaderFontCatalog.displayName(key)
}

private val FontMimeTypes = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/font-sfnt",
    "application/octet-stream",
)
