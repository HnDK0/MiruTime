package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.library.domain.ContentType

/** Translation is a text feature; manga and video plugins never expose it. */
fun supportsPluginTranslation(type: ContentType): Boolean = type == ContentType.Novel
