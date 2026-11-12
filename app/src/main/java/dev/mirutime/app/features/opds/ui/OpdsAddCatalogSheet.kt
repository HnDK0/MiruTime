package dev.mirutime.app.features.opds.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.mirutime.app.R
import dev.mirutime.app.features.finder.domain.OpdsSources
import dev.mirutime.app.features.opds.domain.OpdsController
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Adding a saved OPDS catalog — the ONE OPDS surface left in the app. Saved
// catalogs are ordinary Finder sources, so there is no list here; the reply is
// the source id the caller reloads.
// URL is required and must be http(s); the title defaults to the host. Adding
// writes only the record, so failures here are storage/validation ones — the
// feed itself is checked when the catalog opens.
@Composable
fun OpdsAddCatalogSheet(
    controller: OpdsController,
    onClose: () -> Unit,
    onAdded: (sourceId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<MiruError?>(null) }

    val trimmedUrl = url.trim()
    val validUrl = isHttpUrl(trimmedUrl)

    fun submit() {
        if (busy || !validUrl) return
        busy = true
        error = null
        scope.launch {
            try {
                val title = name.trim().ifBlank { opdsHostOf(trimmedUrl) }
                when (val result = controller.add(title, trimmedUrl, login.trim(), password)) {
                    is Outcome.Ok -> {
                        busy = false
                        onAdded(OpdsSources.idOf(result.value.id))
                    }

                    is Outcome.Err -> {
                        busy = false
                        error = result.error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                error = OpdsFailure.Unknown(e.javaClass.simpleName)
            }
        }
    }

    AppBottomSheet(
        title = stringResource(R.string.opds_add_title),
        description = stringResource(R.string.opds_add_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.opds_add_submit),
        doneEnabled = validUrl && !busy,
        doneBusy = busy,
        onDone = ::submit,
    ) {
        MiruTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = stringResource(R.string.opds_field_url),
            placeholder = stringResource(R.string.opds_field_url_hint),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.size(MiruSize.IconCompact),
                )
            },
            singleLine = true,
            isError = url.isNotBlank() && !validUrl,
            supportingText = if (url.isNotBlank() && !validUrl) {
                stringResource(R.string.opds_add_invalid_url)
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            showClear = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        MiruTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.opds_field_name),
            placeholder = stringResource(R.string.opds_field_name_hint),
            singleLine = true,
            showClear = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        MiruTextField(
            value = login,
            onValueChange = { login = it },
            label = stringResource(R.string.opds_field_login),
            singleLine = true,
            showClear = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        MiruTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.opds_field_password),
            singleLine = true,
            revealable = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Text(
                text = opdsFailureText(it, R.string.opds_add_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
