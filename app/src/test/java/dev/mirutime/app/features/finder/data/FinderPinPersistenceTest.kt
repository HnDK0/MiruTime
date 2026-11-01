package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.KeyValueStore
import dev.mirutime.app.features.settings.data.PersistedSettings
import dev.mirutime.app.features.settings.data.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderPinPersistenceTest {

    @Test
    fun `successful write keeps the optimistic pin`() = runTest {
        withSettings(pinnedSources = emptyList()) {
            val written = persistPinnedSource(
                LocalSources.Books,
                SettingsStore(Store(writeSucceeds = true)),
            )

            assertTrue(written)
            assertTrue(AppSettings.pinnedSources.contains(LocalSources.Books))
        }
    }

    @Test
    fun `failed write reverts the optimistic pin`() = runTest {
        withSettings(pinnedSources = emptyList()) {
            val written = persistPinnedSource(
                LocalSources.Manga,
                SettingsStore(Store(writeSucceeds = false)),
            )

            assertFalse(written)
            assertTrue(AppSettings.pinnedSources.isEmpty())
        }
    }

    private suspend fun withSettings(
        pinnedSources: List<String>,
        block: suspend () -> Unit,
    ) {
        val previous = AppSettings.persistedSnapshot()
        try {
            AppSettings.applyPersisted(PersistedSettings(pinnedSources = pinnedSources))
            block()
        } finally {
            AppSettings.applyPersisted(previous)
        }
    }

    private class Store(private val writeSucceeds: Boolean) : KeyValueStore {
        private val values = mutableMapOf<String, Any>()

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getString(key: String): String? = values[key] as? String
        override fun getInt(key: String): Int? = values[key] as? Int
        override fun getLong(key: String): Long? = values[key] as? Long
        override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

        override fun put(values: Map<String, Any>): Boolean {
            if (!writeSucceeds) return false
            this.values.putAll(values)
            return true
        }
    }
}
