package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 69shuba searches with a GBK-encoded query: only `language=zh` sources get the
// "type Chinese" hint. No auto-translation, no hint on en/ru/local sources.
class ShubaSearchHintTest {

    @Test
    fun chineseSourceShowsHintEvenWithAnEmptyQuery() {
        assertTrue(shouldShowSearchLanguageHint("zh", ""))
        assertTrue(shouldShowSearchLanguageHint("zh", "诡秘"))
        assertTrue(shouldShowSearchLanguageHint("ZH", "诡秘之主"))
    }

    @Test
    fun otherSourcesDoNotShowTheHint() {
        assertFalse(shouldShowSearchLanguageHint("en", ""))
        assertFalse(shouldShowSearchLanguageHint("ru", "книга"))
        assertFalse(shouldShowSearchLanguageHint(null, ""))
        assertFalse(shouldShowSearchLanguageHint("", ""))
    }
}
