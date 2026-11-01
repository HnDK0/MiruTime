package dev.mirutime.app.shell

import androidx.annotation.StringRes
import dev.mirutime.app.R

// Bottom-nav destinations. Subtitles explain each screen.
enum class MiruDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Dashboard(
        route = "dashboard",
        titleRes = R.string.nav_dashboard,
        descriptionRes = R.string.nav_dashboard_desc,
    ),
    Library(
        route = "library",
        titleRes = R.string.nav_library,
        descriptionRes = R.string.nav_library_desc,
    ),
    Finder(
        route = "finder",
        titleRes = R.string.nav_finder,
        descriptionRes = R.string.nav_finder_desc,
    ),
    Settings(
        route = "settings",
        titleRes = R.string.nav_settings,
        descriptionRes = R.string.nav_settings_desc,
    ),
}
