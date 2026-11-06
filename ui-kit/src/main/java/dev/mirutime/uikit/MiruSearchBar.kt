package dev.mirutime.uikit

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction

/**
 * Search input with the app IME policy: Search submits and drops focus, the
 * clear action empties the query. State and network/debounce behaviour still
 * belong to the caller; the Tune button is a sibling, never inside the field.
 */
// One idle window for local/DB filtering; networked catalogues keep their own.
const val MiruSearchDebounceMillis = 300L

@Composable
fun MiruSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
    clearContentDescription: String = stringResource(R.string.field_clear_search),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    plate: Boolean = true,
    onSearch: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    MiruSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        onClear = onClear ?: { onValueChange("") },
        clearContentDescription = clearContentDescription,
        modifier = modifier,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearch?.invoke()
                focus.clearFocus()
            },
        ),
        leadingIcon = leadingIcon,
        trailing = trailing,
        focusRequester = focusRequester,
        onFocusChanged = onFocusChanged,
        plate = plate,
    )
}
