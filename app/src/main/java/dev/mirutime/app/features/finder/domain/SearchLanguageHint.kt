package dev.mirutime.app.features.finder.domain

// 69shuba (and any plugin declaring language=zh) searches with a GBK-encoded
// query: the user must type Chinese, not pinyin/English. A language-aware hint
// is the only help we give — auto-translation is out of scope.
// `query` is part of the UI contract (the hint sits under the query field) but
// does not change the answer: an empty field needs the hint just as much.
fun shouldShowSearchLanguageHint(language: String?, @Suppress("UNUSED_PARAMETER") query: String): Boolean =
    language?.trim()?.equals("zh", ignoreCase = true) == true
