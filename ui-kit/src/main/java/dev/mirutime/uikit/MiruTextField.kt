package dev.mirutime.uikit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow

/**
 * THE single text input. 40dp filled frame, optional label above (only when
 * the value is not self-evident), optional supporting/error line below, clear
 * and reveal actions inside the 40dp target, IME handled by the caller.
 *
 * `revealable` and `visualTransformation` are mutually exclusive: a secret
 * field owns its transformation, everything else may pass its own.
 */
@Composable
fun MiruTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    revealable: Boolean = false,
    showClear: Boolean = false,
    onClear: (() -> Unit)? = null,
    clearContentDescription: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    plate: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    require(!(revealable && visualTransformation != VisualTransformation.None)) {
        "revealable and visualTransformation are mutually exclusive"
    }
    val extra = LocalMiruExtraColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var hidden by remember { mutableStateOf(false) }
    val transformation = if (revealable && hidden) {
        PasswordVisualTransformation()
    } else {
        visualTransformation
    }
    val fieldMinLines = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val fieldMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(fieldMinLines)
    MiruFieldLabels(
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        isError = isError,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .let {
                    if (onFocusChanged != null) {
                        it.onFocusChanged { state -> onFocusChanged(state.isFocused) }
                    } else {
                        it
                    }
                }
                .semantics {
                    label?.let { contentDescription = it }
                    if (isError && supportingText != null) error(supportingText)
                },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = fieldMinLines,
            maxLines = fieldMaxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            textStyle = textStyle.copy(
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            ),
            cursorBrush = SolidColor(extra.accent),
            visualTransformation = transformation,
            decorationBox = { inner ->
                MiruFieldDecoration(
                    isEmpty = value.isEmpty(),
                    placeholder = placeholder,
                    focused = focused,
                    enabled = enabled,
                    isError = isError,
                    plate = plate,
                    singleLine = singleLine,
                    leadingIcon = leadingIcon,
                    trailing = {
                        if (showClear && value.isNotEmpty() && enabled && !readOnly) {
                            FieldIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = clearContentDescription
                                    ?: stringResource(R.string.field_clear),
                                enabled = enabled,
                            ) {
                                if (onClear != null) onClear() else onValueChange("")
                            }
                        }
                        if (revealable) {
                            FieldIconButton(
                                icon = if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = stringResource(
                                    if (hidden) R.string.field_show_secret else R.string.field_hide_secret,
                                ),
                                enabled = enabled,
                            ) { hidden = !hidden }
                        }
                        trailing?.invoke(this)
                    },
                    innerTextField = inner,
                )
            },
        )
    }
}

/** Value-based overload for chrome that owns its selection (browser address bar). */
@Composable
fun MiruTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    showClear: Boolean = false,
    onClear: (() -> Unit)? = null,
    clearContentDescription: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    plate: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldMinLines = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val fieldMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(fieldMinLines)
    MiruFieldLabels(
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        isError = isError,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .let {
                    if (onFocusChanged != null) {
                        it.onFocusChanged { state -> onFocusChanged(state.isFocused) }
                    } else {
                        it
                    }
                }
                .semantics {
                    label?.let { contentDescription = it }
                    if (isError && supportingText != null) error(supportingText)
                },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = fieldMinLines,
            maxLines = fieldMaxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            textStyle = textStyle.copy(
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            ),
            cursorBrush = SolidColor(extra.accent),
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                MiruFieldDecoration(
                    isEmpty = value.text.isEmpty(),
                    placeholder = placeholder,
                    focused = focused,
                    enabled = enabled,
                    isError = isError,
                    plate = plate,
                    singleLine = singleLine,
                    leadingIcon = leadingIcon,
                    trailing = {
                        if (showClear && value.text.isNotEmpty() && enabled && !readOnly) {
                            FieldIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = clearContentDescription
                                    ?: stringResource(R.string.field_clear),
                                enabled = enabled,
                            ) {
                                if (onClear != null) onClear() else onValueChange(TextFieldValue(""))
                            }
                        }
                        trailing?.invoke(this)
                    },
                    innerTextField = inner,
                )
            },
        )
    }
}

@Composable
private fun MiruFieldLabels(
    modifier: Modifier,
    label: String?,
    supportingText: String?,
    isError: Boolean,
    content: @Composable () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = MiruSpace.S, bottom = MiruSpace.Xxs)
                    .clearAndSetSemantics {},
            )
        }
        content()
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else extra.muted,
                modifier = Modifier.padding(start = MiruSpace.S, top = MiruSpace.Xs),
            )
        }
    }
}

@Composable
private fun MiruFieldDecoration(
    isEmpty: Boolean,
    placeholder: String?,
    focused: Boolean,
    enabled: Boolean,
    isError: Boolean,
    plate: Boolean,
    singleLine: Boolean,
    leadingIcon: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    innerTextField: @Composable () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    MiruFieldFrame(
        focused = focused,
        enabled = enabled,
        isError = isError,
        plate = plate,
    ) {
        if (leadingIcon != null) {
            Box(Modifier.size(MiruSize.Control), contentAlignment = Alignment.Center) {
                leadingIcon()
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (isEmpty && placeholder != null) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            innerTextField()
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun FieldIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = Modifier
            .size(MiruSize.Control)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) extra.muted else extra.muted.copy(alpha = 0.38f),
            modifier = Modifier.size(MiruSize.IconCompact),
        )
    }
}
