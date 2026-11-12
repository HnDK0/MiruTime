package dev.mirutime.app.features.opds.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.opds.OpdsError
import java.util.Locale

/** Host shown as the secondary line of a saved catalog; falls back to the raw address. */
internal fun opdsHostOf(url: String): String {
    val trimmed = url.trim()
    return Uri.parse(trimmed).host ?: trimmed
}

/** A URL we can hand to the network layer: http(s) with a real host. */
internal fun isHttpUrl(text: String): Boolean {
    val uri = Uri.parse(text.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}

/** Localized copy for a catalog failure; null when the cause has no specific wording. */
@StringRes
internal fun opdsFailureRes(error: MiruError): Int? = when (error) {
    OpdsFailure.NeedsLogin -> R.string.opds_err_needs_login
    OpdsFailure.DigestOnly -> R.string.opds_err_digest
    OpdsFailure.Forbidden -> R.string.opds_err_forbidden
    OpdsFailure.NotDownloadable -> R.string.opds_no_format
    OpdsFailure.NotAFeed, OpdsError.NotAFeed -> R.string.opds_err_not_feed
    OpdsFailure.JsonCatalog, OpdsError.JsonCatalogUnsupported -> R.string.opds_err_json
    OpdsFailure.Malformed, OpdsError.Malformed -> R.string.opds_err_malformed
    else -> null
}

/**
 * One mapping for every OPDS surface: a specific reason when we have one,
 * [fallback] otherwise (opening a feed / adding a catalog / re-login each have
 * their own neutral sentence). Network status is the only failure carrying an
 * argument; it never prints the address.
 */
@Composable
internal fun opdsFailureText(error: MiruError, @StringRes fallback: Int): String {
    // Not a network problem: the file is simply over the download budget.
    if (error is OpdsFailure.TooLarge) {
        return stringResource(R.string.opds_err_too_large_fmt, error.limitMb)
    }
    if (error is OpdsFailure.Network) {
        return error.status?.let { stringResource(R.string.opds_err_network_fmt, it) }
            ?: stringResource(R.string.opds_err_network)
    }
    return stringResource(opdsFailureRes(error) ?: fallback)
}
