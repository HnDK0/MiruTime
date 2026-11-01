package dev.mirutime.app.features.settings.ui

import dev.mirutime.app.R
import dev.mirutime.app.features.library.data.LibraryUpdateStatus
import dev.mirutime.app.features.updates.data.AppUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryUpdatesSettingsScreensTest {

    @Test
    fun `update check failure uses the stable localized string`() {
        assertEquals(
            R.string.st_updates_error,
            updateResultMessageRes(AppUpdateResult.Error("failed for https://api.github.com/repos/...")),
        )
    }

    @Test
    fun `library status error never renders the transport message`() {
        assertEquals(
            R.string.st_updates_status_error,
            libraryStatusRes(LibraryUpdateStatus.Error("failed for https://api.example.com/repo")),
        )
        assertEquals(
            R.string.st_updates_status_error,
            libraryStatusRes(LibraryUpdateStatus.Error(null)),
        )
    }

    @Test
    fun `non-error results keep their formatted strings`() {
        assertEquals(
            R.string.st_updates_available_fmt,
            updateResultMessageRes(AppUpdateResult.Available("v1", "url", null)),
        )
        assertEquals(
            R.string.st_updates_latest_fmt,
            updateResultMessageRes(AppUpdateResult.UpToDate("v1")),
        )
        assertEquals(
            R.string.st_updates_no_releases,
            updateResultMessageRes(AppUpdateResult.NoReleases),
        )
    }
}
