package dev.mirutime.app.features.settings.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app language without AppCompat. MainActivity wraps its base context with
 * the saved locale, so every Compose string and Toast resolves in the chosen
 * language; changing the setting recreates the activity so visible strings
 * update immediately.
 *
 * Precedence: an explicit in-app choice always wins over the OS per-app locale
 * (`android:localeConfig`). Only [System] follows the platform.
 */
object AppLanguage {
    const val System = "system"

    /** Null means "follow the system locale". Unknown tags also fall back. */
    fun localeFor(code: String): Locale? {
        if (code.isBlank() || code == System) return null
        val locale = runCatching { Locale.forLanguageTag(code) }.getOrNull() ?: return null
        return locale.takeIf { it.language.isNotBlank() }
    }

    fun wrap(base: Context, code: String): Context {
        val locale = localeFor(code) ?: return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    /**
     * True only when a restart would change the language. "system" compares
     * against the device locale, so switching back from an override recreates
     * once; comparing languages (not tags) avoids a loop on region variants.
     */
    fun needsRecreate(current: Locale, code: String): Boolean {
        val requested = localeFor(code) ?: Locale.getDefault()
        return requested.language != current.language
    }
}
