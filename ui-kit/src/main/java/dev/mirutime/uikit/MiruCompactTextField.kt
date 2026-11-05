package dev.mirutime.uikit

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/**
 * Compact single-line input for dense sheets: a small visual field inside a
 * full-size touch target, with the text centered vertically in the field.
 * Use it instead of an OutlinedTextField when the surrounding row is compact.
 */
@Composable
fun MiruCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    contentDescription: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    width: Dp = MiruSize.Control + MiruSpace.Xl,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Control)
    val border = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val description = contentDescription
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val fieldWidth = width * fontScale
    val visualHeight = (MiruSize.Chip + MiruSpace.Xs) * fontScale
    val touchHeight = maxOf(MiruSize.Touch, visualHeight)
    val semanticsModifier = if (description == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = description }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = semanticsModifier
            .width(fieldWidth)
            .height(touchHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(visualHeight)
                .clip(shape)
                .border(MiruSize.Stroke, border, shape),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = textStyle.copy(
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MiruSpace.S),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = extra.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
