package dev.mirutime.uikit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// === Settings building blocks ===
// A settings screen is a LazyColumn of MiruSectionCards (contentPadding 0);
// inside a card the rows below are separated by MiruRowDivider. Every row has
// the 56dp minimum, pads itself L/S and is fully tappable. Rows never carry
// their own surface.

// Page scaffold: standard screen padding and the one block gap.
@Composable
fun SettingsPage(
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MiruSize.ContentMaxWidth).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
            contentPadding = PaddingValues(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
                bottom = contentPadding.calculateBottomPadding() + MiruSize.Touch,
            ),
            content = content,
        )
    }
}

@Composable
private fun RowTexts(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

// Title + optional description, Switch on the right.
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    horizontalPadding: Dp = MiruSpace.L,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = horizontalPadding, vertical = MiruSpace.S),
    ) {
        RowTexts(title, subtitle, Modifier.weight(1f), enabled)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            // The theme's muted ink keeps the off state readable on every
            // palette: a solid thumb, a tinted track and a stronger border.
            // On keeps the card accent, disabled dims the same pair instead of
            // falling back to Material's near-invisible outline.
            colors = SwitchDefaults.colors(
                checkedThumbColor = extra.onAccent,
                checkedTrackColor = extra.accent,
                checkedBorderColor = extra.accent,
                checkedIconColor = extra.onAccent,
                uncheckedThumbColor = extra.muted,
                uncheckedTrackColor = extra.muted.copy(alpha = 0.32f),
                uncheckedBorderColor = extra.muted.copy(alpha = 0.55f),
                uncheckedIconColor = extra.muted,
                disabledCheckedThumbColor = extra.onAccent.copy(alpha = 0.70f),
                disabledCheckedTrackColor = extra.accent.copy(alpha = 0.40f),
                disabledCheckedBorderColor = extra.accent.copy(alpha = 0.40f),
                disabledCheckedIconColor = extra.onAccent.copy(alpha = 0.50f),
                disabledUncheckedThumbColor = extra.muted.copy(alpha = 0.60f),
                disabledUncheckedTrackColor = extra.muted.copy(alpha = 0.22f),
                disabledUncheckedBorderColor = extra.muted.copy(alpha = 0.40f),
            ),
        )
    }
}

// Title (+ description) on the left, current value and an arrow on the right;
// tap opens a choice sheet / screen.
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Settings cards pad rows themselves; a sheet already pads its content,
    // so a row hosted flat in a sheet passes 0.dp.
    horizontalPadding: Dp = MiruSpace.L,
) {
    val extra = LocalMiruExtraColors.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = horizontalPadding, vertical = MiruSpace.S),
    ) {
        val maxValueWidth = maxWidth / 2
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            RowTexts(title, subtitle, Modifier.weight(1f), enabled)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: if (enabled) extra.accent else extra.muted,
                modifier = Modifier.widthIn(max = maxValueWidth).padding(start = MiruSpace.S),
            )
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = extra.muted,
                    modifier = Modifier.size(MiruSize.Indicator),
                )
            }
        }
    }
}

// Read-only status row: title on the left, value on the right, no tap target
// and no forward arrow. Used for "last backup", permission and schedule state.
@Composable
fun SettingsInfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueColor: Color? = null,
) {
    val extra = LocalMiruExtraColors.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
    ) {
        val maxValueWidth = maxWidth / 2
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            RowTexts(title, subtitle, Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: extra.muted,
                modifier = Modifier.widthIn(max = maxValueWidth).padding(start = MiruSpace.S),
            )
        }
    }
}

// Slider with a live value label. `valueText` is already formatted.
@Composable
fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: Dp = MiruSpace.L,
) {
    val extra = LocalMiruExtraColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = MiruSpace.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTexts(title, subtitle, Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = extra.accent,
            )
        }
        MiruSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            contentDescription = title,
        )
    }
}

// One compact line: label on the left, a caller-drawn slider in the middle,
// the formatted value on the right. Unlike SettingsSliderRow it does not own
// the slider, so the row can host stepped or custom controls.
@Composable
fun MiruLabeledSliderRow(
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MiruSize.Touch)
            .padding(vertical = MiruSpace.Xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = MiruSize.SliderLabelWidth),
        )
        Spacer(Modifier.width(MiruSpace.M))
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(MiruSpace.M))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = MiruSize.SliderValueWidth),
        )
    }
}

// Shared labelled field for keys, URLs and prompts; delegates to MiruTextField
// so settings and screens use the same chrome. `error` turns it red and shows
// the message instead of the tip.
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    tip: String? = null,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    revealable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
    ) {
        MiruTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            placeholder = placeholder,
            isError = error != null,
            supportingText = error ?: tip,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            revealable = revealable,
        )
    }
}

// Radio row used for provider-style single choices inside a card.
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    MiruPickerRow(
        title = title,
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = MiruSize.Row),
        meta = subtitle,
        leading = { RadioButton(selected = selected, onClick = null) },
    )
}

// Quiet explanatory line at the bottom of a card.
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalMiruExtraColors.current.muted,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
    )
}

// Single choice in a bottom sheet: tap applies immediately; the sheet stays
// open so the result can be compared; Close dismisses.
data class ChoiceOption<T>(val key: T, val label: String, val description: String? = null)

@Composable
fun <T> ChoiceSheet(
    title: String,
    description: String,
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onClose: () -> Unit,
) {
    AppBottomSheet(
        title = title,
        description = description,
        onClose = onClose,
    ) {
        options.forEach { option ->
            SettingsRadioRow(
                title = option.label,
                subtitle = option.description,
                selected = option.key == selected,
                onClick = {
                    onSelect(option.key)
                },
            )
        }
    }
}
