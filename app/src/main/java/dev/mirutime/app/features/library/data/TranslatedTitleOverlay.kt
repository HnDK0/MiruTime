package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.translation.data.MetadataConfig
import dev.mirutime.app.features.translation.data.MetadataTranslationStore
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.translation.data.storeKey
import dev.mirutime.app.features.translation.data.translationPresentationFor
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.libraryBookKey

/**
 * Cold-start title overlay: one persisted lookup per distinct active config,
 * never one per book. Books are keyed by installId + URL, the same key the live
 * title lane uses. A book without a FULL pair, whose stored row does not match
 * its current key, or whose owner hid translated titles on [surface] keeps the
 * original title.
 */
internal suspend fun loadTranslatedTitles(
    books: List<BookEntity>,
    store: MetadataTranslationStore?,
    surface: TitleSurface = TitleSurface.Library,
): Map<Long, String> {
    if (store == null || books.isEmpty()) return emptyMap()
    val keysByConfig = LinkedHashMap<MetadataConfig, MutableList<String>>()
    books.forEach { book ->
        val bookKey = libraryBookKey(book.installId, book.url)
        if (!translationPresentationFor(bookKey).showsTitle(surface)) return@forEach
        val config = metadataConfigFor(bookKey) ?: return@forEach
        keysByConfig.getOrPut(config) { mutableListOf() }.add(bookKey)
    }
    if (keysByConfig.isEmpty()) return emptyMap()

    val idByKey = books.associate { libraryBookKey(it.installId, it.url) to it.bookId }
    val titles = mutableMapOf<Long, String>()
    keysByConfig.forEach { (config, keys) ->
        val stored = runCatching { store.loadTitles(keys.distinct(), config.storeKey()) }
            .getOrDefault(emptyMap())
        stored.forEach { (bookKey, translation) ->
            val bookId = idByKey[bookKey] ?: return@forEach
            translation.title?.takeIf { it.isNotBlank() }?.let { titles[bookId] = it }
        }
    }
    return titles
}
