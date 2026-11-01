package dev.mirutime.app.features.settings.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mirutime.app.R
import dev.mirutime.app.features.translation.data.LocalNetworkAccess
import dev.mirutime.app.features.translation.data.TranslationBaseUrlCheck
import dev.mirutime.app.features.translation.data.checkTranslationBaseUrl
import dev.mirutime.app.features.translation.data.translationEndpointError
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import kotlinx.coroutines.delay

/**
 * Resource for a typed endpoint preflight error. The settings screen and the
 * request path read the same [translationEndpointError] policy, so the field
 * cannot promise a URL that the manager rejects.
 */
@StringRes
internal fun endpointErrorRes(error: TranslationError?): Int? = when (error) {
    is TranslationError.LocalNetworkRequired -> R.string.st_tr_local_network_required
    is TranslationError.CleartextNotAllowed -> R.string.st_tr_cleartext_forbidden
    else -> null
}

@StringRes
internal fun baseUrlIssueRes(issue: TranslationBaseUrlCheck): Int? = when (issue) {
    TranslationBaseUrlCheck.Ok -> null
    TranslationBaseUrlCheck.MissingScheme -> R.string.st_tr_url_missing_scheme
    TranslationBaseUrlCheck.UnsupportedScheme -> R.string.st_tr_url_unsupported_scheme
    TranslationBaseUrlCheck.MissingHost -> R.string.st_tr_url_missing_host
    TranslationBaseUrlCheck.InvalidUrl -> R.string.st_tr_url_invalid
}

/** Syntax first, then cleartext/permission: the field shows one message. */
@StringRes
internal fun baseUrlFieldErrorRes(
    issue: TranslationBaseUrlCheck,
    endpointError: TranslationError?,
): Int? = baseUrlIssueRes(issue) ?: endpointErrorRes(endpointError)

class LocalNetworkGrantState(
    val granted: Boolean,
    val denied: Boolean,
    val request: () -> Unit,
)

class LocalNetworkPermissionState(
    val required: Boolean,
    val denied: Boolean,
    @StringRes val errorRes: Int?,
    val request: () -> Unit,
)

/**
 * Live runtime-grant state. The system owns the value, so it is re-read on
 * every resume: a grant made in system settings is picked up without an app
 * restart.
 */
@Composable
fun rememberLocalNetworkGrant(): LocalNetworkGrantState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(LocalNetworkAccess.isGranted(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = LocalNetworkAccess.isGranted(context)
                if (granted) denied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return LocalNetworkGrantState(
        granted = granted,
        denied = denied,
        request = {
            // After "don't ask again" the system ignores another launch, so
            // the row opens this app's settings page instead of doing nothing.
            val rationale = context.findActivity()
                ?.shouldShowRequestPermissionRationale(LocalNetworkAccess.Permission)
                ?: true
            val openedSettings = shouldOpenAppSettings(denied, rationale) && openAppSettings(context)
            if (!openedSettings) launcher.launch(LocalNetworkAccess.Permission)
        },
    )
}

/** True only for a denial the system will not prompt for again. */
internal fun shouldOpenAppSettings(denied: Boolean, showRationale: Boolean): Boolean =
    denied && !showRationale

/**
 * Runtime grant for the local translation server. Settings have no Save
 * button, so the prompt fires once a local endpoint settles in the field;
 * after a denial the screen keeps an explicit retry row instead of asking
 * again on every edit.
 */
@Composable
fun rememberLocalNetworkPermission(
    provider: ProviderId,
    baseUrl: String,
): LocalNetworkPermissionState {
    val grant = rememberLocalNetworkGrant()
    var asked by remember { mutableStateOf(false) }
    val syntax = rememberDebouncedBaseUrlIssue(baseUrl)
    val error = translationEndpointError(
        provider = provider,
        baseUrl = baseUrl,
        sdkInt = Build.VERSION.SDK_INT,
        granted = grant.granted,
    )
    val required = error is TranslationError.LocalNetworkRequired
    LaunchedEffect(provider, baseUrl, required) {
        if (!required || asked) return@LaunchedEffect
        delay(800)
        asked = true
        grant.request()
    }
    return LocalNetworkPermissionState(
        required = required,
        denied = grant.denied,
        errorRes = baseUrlFieldErrorRes(syntax, error),
        request = grant.request,
    )
}

/**
 * Display-only: settings already persist on every keystroke, so the syntax
 * message waits for a short typing pause instead of flickering per character.
 */
@Composable
private fun rememberDebouncedBaseUrlIssue(baseUrl: String): TranslationBaseUrlCheck {
    var issue by remember { mutableStateOf(checkTranslationBaseUrl(baseUrl)) }
    LaunchedEffect(baseUrl) {
        delay(300)
        issue = checkTranslationBaseUrl(baseUrl)
    }
    return issue
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** App details page is the only place to undo "don't ask again". */
private fun openAppSettings(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess
