package dev.mirutime.app.features.settings.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// Real battery-optimization state. Nothing here is stored: the system is the
// only source of truth, and Settings refreshes it on every resume.
enum class BatteryOptimizationState {
    Ignored,
    Optimized,
    Unsupported,
    ;

    companion object {
        /** Pure mapping used by the UI and its unit test. */
        fun map(ignoring: Boolean, actionAvailable: Boolean): BatteryOptimizationState = when {
            ignoring -> Ignored
            actionAvailable -> Optimized
            else -> Unsupported
        }
    }
}

data class BatteryOptimizationStatus(
    val ignoring: Boolean,
    val requestAvailable: Boolean,
    val settingsAvailable: Boolean,
) {
    val state: BatteryOptimizationState
        get() = BatteryOptimizationState.map(
            ignoring = ignoring,
            actionAvailable = requestAvailable || settingsAvailable,
        )

    val actionAvailable: Boolean
        get() = requestAvailable || settingsAvailable
}

object BatteryOptimization {
    fun status(context: Context): BatteryOptimizationStatus {
        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val settings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return BatteryOptimizationStatus(
            ignoring = ignoring,
            requestAvailable = canResolve(context, request),
            settingsAvailable = canResolve(context, settings),
        )
    }

    /**
     * Opens the system opt-out dialog when possible, otherwise the battery
     * optimization list. Returns false when the device exposes neither; the
     * caller must report that instead of pretending the state changed.
     */
    fun requestIgnore(context: Context): Boolean {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).takeIf { canResolve(context, it) }
        val target = request ?: openSettingsIntent(context) ?: return false
        return launch(context, target)
    }

    /** Read-only management screen for users who already opted out. */
    fun openSettings(context: Context): Boolean =
        openSettingsIntent(context)?.let { launch(context, it) } ?: false

    private fun openSettingsIntent(context: Context): Intent? =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .takeIf { canResolve(context, it) }

    private fun launch(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    private fun canResolve(context: Context, intent: Intent): Boolean =
        runCatching {
            intent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
}
