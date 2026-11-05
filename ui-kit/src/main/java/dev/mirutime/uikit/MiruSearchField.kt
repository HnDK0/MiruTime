package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow

/** String convenience adapter; IME policy and all query state belong to its caller. */
@Composable
fun MiruSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val extra = LocalMiruExtraColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
        ),
        cursorBrush = SolidColor(extra.accent),
        decorationBox = { innerTextField ->
            MiruSearchDecoration(
                innerTextField = innerTextField,
                showPlaceholder = value.isEmpty(),
                placeholder = {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                focused = focused,
                enabled = enabled,
                leadingIcon = leadingIcon,
                trailing = {
                    if (value.isNotEmpty()) {
                        MiruIconButton(Icons.Filled.Close, clearContentDescription, onClear, enabled = enabled)
                    }
                    trailing?.invoke(this)
                },
            )
        },
    )
}

/** Visual frame only: a TextFieldValue/URI/state-based input can supply its own inner field. */
@Composable
fun MiruSearchDecoration(
    innerTextField: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    showPlaceholder: Boolean = false,
    focused: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MiruSize.Row)
            .background(miruCardSurface(), shape)
            .border(
                if (focused) MiruSpace.Xxs else MiruSize.Stroke,
                if (focused && enabled) extra.accent else scheme.outlineVariant,
                shape,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(MiruSize.Touch), contentAlignment = Alignment.Center) {
            if (leadingIcon != null) {
                leadingIcon()
            } else {
                Icon(Icons.Filled.Search, contentDescription = null, tint = extra.muted, modifier = Modifier.size(MiruSize.Icon))
            }
        }
        Box(
            modifier = Modifier.weight(1f).padding(vertical = MiruSpace.S),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (showPlaceholder) placeholder?.invoke()
            innerTextField()
        }
        trailing?.invoke(this)
    }
}
