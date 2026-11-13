package dev.mirutime.uikit

import androidx.compose.runtime.staticCompositionLocalOf
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The language the interface is in. Numbers have to follow it: resources format
 * `%d` with the configuration locale, so an Arabic UI already shows Arabic-Indic
 * counters while code that formats with [Locale.US] would print ASCII digits in
 * the same row.
 *
 * Provided once at the root of each activity; the default is the device locale,
 * which is what a screen without a provider is rendered with anyway.
 */
val LocalMiruLocale = staticCompositionLocalOf { Locale.getDefault() }

/**
 * A decimal number in [locale], at most [decimals] fraction digits, no trailing
 * zeros and no grouping: "4.5", "١٢٫٥", "12,5".
 *
 * Goes through [String.format] on purpose — the same formatter the resource
 * system uses, so a counter here and a `%d` from `strings.xml` always agree.
 * `NumberFormat` does not: it picks digits from the locale's numbering system,
 * which differs between the host JVM (Latin) and Android (Arabic-Indic).
 */
fun miruDecimal(value: Number, decimals: Int, locale: Locale): String {
    val raw = String.format(locale, "%.${decimals}f", value.toDouble())
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    if (separator !in raw) return raw
    val trimmed = raw.trimEnd('0')
    return if (trimmed.endsWith(separator)) trimmed.dropLast(separator.toString().length) else trimmed
}
