package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.translation.data.MetadataConfig
import dev.mirutime.app.features.translation.data.MetadataTranslationStore
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.translation.data.storeKey
import dev.mirutime.app.features.translation.data.translationPresentationFor
import dev.mirutime.data.library.BookEntity

/**
 * Cold-start title overlay: one persisted lookup per distinct active config,
 * never one per book. A book without a FULL pair, whose stored row does not
 * match its current key, or whose owner hid translated titles on [surface]
 * keeps the original title.
 */
internal suspend fun loadTranslatedTitles(
    books: List<BookEntity>,
    store: MetadataTranslationStore?,
    surface: TitleSurface = TitleSurface.Library,
): Map<Long, String> {
    if (store == null || books.isEmpty()) return emptyMap()
    val urlsByConfig = LinkedHashMap<MetadataConfig, MutableList<String>>()
    books.forEach { book ->
        if (!translationPresentationFor(book.bookUrl).showsTitle(surface)) return@forEach
        val config = metadataConfigFor(book.bookUrl) ?: return@forEach
        urlsByConfig.getOrPut(config) { mutableListOf() }.add(book.bookUrl)
    }
    if (urlsByConfig.isEmpty()) return emptyMap()

    val idByUrl = books.associate { it.bookUrl to it.bookId }
    val titles = mutableMapOf<Long, String>()
    urlsByConfig.forEach { (config, urls) ->
        val stored = runCatching { store.loadTitles(urls.distinct(), config.storeKey()) }
            .getOrDefault(emptyMap())
        stored.forEach { (url, translation) ->
            val bookId = idByUrl[url] ?: return@forEach
            translation.title?.takeIf { it.isNotBlank() }?.let { titles[bookId] = it }
        }
    }
    return titles
}
