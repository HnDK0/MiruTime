package dev.mirutime.app.features.settings.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.miruDecimal
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.SettingsLanguages
import java.text.Collator
import java.util.Locale

// Routes of the Settings tree. A section is its own screen;
// the home screen only lists them. Regex takes an optional novel (per-novel
// rules, opened from the book page).
object SettingsRoutes {
    const val Appearance = "settings/section/appearance"
    const val Library = "settings/section/library"
    const val Updates = "settings/section/updates"
    const val Downloads = "settings/downloads"
    const val Reader = "settings/section/reader"
    const val Translation = "settings/section/translation"
    const val NovelPrompts = "settings/translation/novels"
    const val NovelTranslation = "settings/translation/books"
    const val PluginTranslation = "settings/translation/plugins"
    const val Regex = "settings/regex"
    const val RegexPattern = "settings/regex?novel={novel}"
    const val Network = "settings/section/network"
    const val Data = "settings/section/data"
    const val Backup = "settings/section/backup"
    const val About = "settings/section/about"

    // Used by the shell header: which section a route belongs to.
    fun titleKey(route: String?): String? = when (route) {
        Appearance -> "appearance"
        Library -> "library"
        Updates -> "updates"
        Downloads -> "downloads"
        Reader -> "reader"
        Translation -> "translation"
        NovelPrompts -> "novel_prompts"
        NovelTranslation -> "novel_translation"
        PluginTranslation -> "plugin_translation"
        RegexPattern -> "regex"
        Network -> "network"
        Data -> "data"
        Backup -> "backup"
        About -> "about"
        else -> null
    }
}

// Localized language name written in that language ("Русский", "Deutsch").
fun nativeLanguageName(code: String): String {
    val tag = if (code == "in") "id" else code
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale)
        .replaceFirstChar { it.titlecase(locale) }
        .ifBlank { code }
}

// The language the interface is actually in. Locale.getDefault() is the device
// locale, so it ignores the in-app override.
fun uiLocale(): Locale = AppLanguage.localeFor(AppSettings.appLanguage) ?: Locale.getDefault()

// Language name in the UI language ("Russian" / "Русский") for the
// translation pickers, where the user reads names in their own language.
fun uiLanguageName(code: String): String {
    val tag = if (code == "in") "id" else code
    val ui = uiLocale()
    return Locale.forLanguageTag(tag).getDisplayLanguage(ui)
        .replaceFirstChar { it.titlecase(ui) }
        .ifBlank { code }
}

fun formatBytes(bytes: Long, locale: Locale): String {
    if (bytes <= 0L) return miruDecimal(0, 0, locale) + " B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(locale, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(locale, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(locale, "%.0f KB", bytes / kb)
        else -> miruDecimal(bytes, 0, locale) + " B"
    }
}

@Composable
fun appLanguageOptions(): List<ChoiceOption<String>> =
    listOf(ChoiceOption("system", stringResource(R.string.st_language_system))) +
        SettingsLanguages.app
            .map { code -> code to nativeLanguageName(code) }
            // Code-point order would sort every Arabic name by its second letter.
            .sortedWith(compareBy(Collator.getInstance(uiLocale())) { it.second })
            .map { (code, name) -> ChoiceOption(code, name) }

@Composable
fun appLanguageLabel(code: String): String =
    if (code == "system") stringResource(R.string.st_language_system) else nativeLanguageName(code)

// Single catalog of the ten translator providers (matches
// dev.mirutime.translator.ProviderId). `key` is the persisted
// TRANSLATION_PROVIDER value; `isLlm` drives the prompt and token controls,
// `maxChunkConcurrency` mirrors the translator's per-provider cap.
enum class TranslationProviderOption(
    val key: String,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val isLlm: Boolean = false,
    val maxChunkConcurrency: Int = 32,
) {
    GooglePa("GOOGLE_PA", R.string.st_provider_google_pa, R.string.st_provider_google_pa_desc),
    GoogleFree("GOOGLE_FREE", R.string.st_provider_google_free, R.string.st_provider_google_free_desc),
    Gemini("GEMINI", R.string.st_provider_gemini, R.string.st_provider_gemini_desc, isLlm = true),
    OpenAi("OPENAI", R.string.st_provider_openai, R.string.st_provider_openai_desc, isLlm = true),
    OpenAiCompatible(
        "OPENAI_COMPATIBLE",
        R.string.st_provider_openai_compatible,
        R.string.st_provider_openai_compatible_desc,
        isLlm = true,
    ),
    Anthropic("ANTHROPIC", R.string.st_provider_anthropic, R.string.st_provider_anthropic_desc, isLlm = true),
    DeepL("DEEPL", R.string.st_provider_deepl, R.string.st_provider_deepl_desc),
    YandexCloud("YANDEX_CLOUD", R.string.st_provider_yandex_cloud, R.string.st_provider_yandex_cloud_desc),
    YandexFree("YANDEX_FREE", R.string.st_provider_yandex_free, R.string.st_provider_yandex_free_desc),
    YandexPa(
        "YANDEX_PA",
        R.string.st_provider_yandex_pa,
        R.string.st_provider_yandex_pa_desc,
        maxChunkConcurrency = 16,
    ),
    ;

    companion object {
        fun from(key: String): TranslationProviderOption =
            entries.firstOrNull { it.key == key } ?: GooglePa
    }
}

@Composable
fun providerLabel(provider: String): String =
    stringResource(TranslationProviderOption.from(provider).label)

@Composable
fun intervalLabel(hours: Int): String = stringResource(
    when (hours) {
        0 -> R.string.st_interval_launch
        12 -> R.string.st_interval_12h
        72 -> R.string.st_interval_72h
        168 -> R.string.st_interval_week
        else -> R.string.st_interval_24h
    },
)

// Header (title, description) string resources of a settings sub-route, null
// for every other route. The shell calls this for the top bar.
fun settingsHeaderRes(route: String?): Pair<Int, Int>? = when (SettingsRoutes.titleKey(route)) {
    "appearance" -> R.string.st_appearance to R.string.st_appearance_header_desc
    "library" -> R.string.st_library to R.string.st_library_header_desc
    "updates" -> R.string.st_updates to R.string.st_updates_header_desc
    "downloads" -> R.string.st_downloads to R.string.st_downloads_header_desc
    "reader" -> R.string.st_reader to R.string.st_reader_header_desc
    "translation" -> R.string.st_translation to R.string.st_translation_header_desc
    "novel_prompts" -> R.string.st_tr_novel_prompts to R.string.st_np_header_desc
    "novel_translation" -> R.string.st_tr_novels to R.string.st_tr_novels_header_desc
    "plugin_translation" -> R.string.st_tr_plugins to R.string.st_pt_header_desc
    "regex" -> R.string.st_regex to R.string.st_regex_header_desc
    "network" -> R.string.st_network to R.string.st_network_header_desc
    "data" -> R.string.st_data to R.string.st_data_header_desc
    "backup" -> R.string.st_backup to R.string.st_backup_header_desc
    "about" -> R.string.st_about to R.string.st_about_header_desc
    else -> null
}
