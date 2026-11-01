package dev.mirutime.app.features.webview.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.NovelPrompt
import dev.mirutime.app.features.settings.data.SettingsLanguages
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.translation.data.composePrompt
import dev.mirutime.app.features.translation.data.pluginIdOf
import dev.mirutime.app.features.translation.data.resolveTranslationPair
import dev.mirutime.app.features.translation.data.resolveTranslationProvider
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import java.util.Locale

/**
 * Language pair, provider and prompt one page translation session runs with.
 * Resolved once at tap time, so a page never starts translating after the fact
 * with a stale pair.
 */
data class PageTranslationConfig(
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec?,
)

private val English = LanguageCode.parse("en")

/**
 * Resolves the page translator with the same cascade as the reader: an enabled
 * per-novel config wins over an enabled plugin, then the global pair/provider.
 * A disabled level never leaks its stored pair. The global level is always
 * allowed here: the WebView action works on demand even when translation is not
 * enabled globally for library pages. The prompt is the active system prompt
 * with the novel prompt either replacing or appending to it; free providers
 * ignore it, LLM providers never sit on the page path.
 *
 * A blank or "auto" source means auto-detect. A blank target follows the device
 * language when the translator offers it, otherwise English. Target must never
 * equal the source: a same-language pair falls back to English, and when the
 * source is already English the device language is the only other choice. Null
 * means nothing sensible is left to translate into and the caller has to say so
 * instead of pretending to work.
 */
fun resolvePageTranslation(
    novel: TranslationConfig?,
    plugin: TranslationConfig?,
    globalSource: String,
    globalTarget: String,
    globalProvider: String,
    deviceLanguage: String,
    activePrompt: String = "",
    novelPrompt: NovelPrompt? = null,
    supported: Set<String> = SettingsLanguages.translation.toSet(),
): PageTranslationConfig? {
    val pair = resolveTranslationPair(
        novel = novel,
        plugin = plugin,
        // Page translation is an explicit on-demand action, so the global
        // languages are the base layer even when global mode is off.
        globalMode = true,
        globalEnabled = true,
        globalSource = globalSource,
        globalTarget = globalTarget,
    )
    val source = pair.source.parseLanguage()
    val explicitTarget = pair.target.parseLanguage()
    val device = deviceLanguage
        .trim()
        .replace('_', '-')
        .substringBefore('-')
        .lowercase()
        .takeIf { it.isNotEmpty() && it in supported }
        ?.parseLanguage()

    var target = explicitTarget ?: device ?: English
    if (target.sameLanguage(source)) {
        target = when {
            source == null || !source.sameLanguage(English) -> English
            device != null && !device.sameLanguage(source) -> device
            else -> return null
        }
    }

    val providerRaw = resolveTranslationProvider(novel, plugin, globalProvider)
    return PageTranslationConfig(
        source = source,
        target = target,
        provider = ProviderId.from(providerRaw) ?: ProviderId.GOOGLE_FREE,
        prompt = composePrompt(
            base = activePrompt,
            override = novelPrompt?.prompt.orEmpty(),
            append = novelPrompt?.append == true,
        ),
    )
}

/**
 * Route context to resolver: a plugin page carries its source id, a book page
 * carries the book URL (which also encodes the plugin id).
 */
fun resolvePageTranslationFor(
    bookUrl: String?,
    sourceId: String?,
    deviceLanguage: String = Locale.getDefault().language,
): PageTranslationConfig? {
    val book = bookUrl?.trim()?.takeIf { it.isNotEmpty() }
    val pluginId = sourceId?.trim()?.takeIf { it.isNotEmpty() }
        ?: book?.let(::pluginIdOf)
    return resolvePageTranslation(
        novel = book?.let { AppSettings.novelTranslation[it] },
        plugin = pluginId?.let { AppSettings.pluginTranslation[it] },
        globalSource = AppSettings.globalSource,
        globalTarget = AppSettings.globalTarget,
        globalProvider = AppSettings.provider,
        deviceLanguage = deviceLanguage,
        activePrompt = AppSettings.activePrompt,
        novelPrompt = book?.let { url ->
            AppSettings.novelPrompts.firstOrNull { it.bookUrl == url }
        },
    )
}

private fun String.parseLanguage(): LanguageCode? =
    runCatching { LanguageCode.parse(this) }.getOrNull()

private fun LanguageCode.sameLanguage(other: LanguageCode?): Boolean =
    other != null && value.substringBefore('-') == other.value.substringBefore('-')
