package dev.mirutime.app.design.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruSearchField

// The one catalogue search field: placeholder-only, leading search glyph,
// clear button, IME Search action. Global search and all migration surfaces
// use this exact field so the two flows cannot drift.
@Composable
fun CatalogSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    MiruSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        onClear = onClear,
        clearContentDescription = stringResource(R.string.finder_cd_clear_search),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier,
    )
}
