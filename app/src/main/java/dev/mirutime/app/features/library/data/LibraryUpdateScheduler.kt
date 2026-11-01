package dev.mirutime.app.features.library.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.settings.data.PersistedSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LibraryUpdateStatus {
    data object Idle : LibraryUpdateStatus
    data object Disabled : LibraryUpdateStatus
    data object Scheduled : LibraryUpdateStatus
    data class LastRun(val checked: Int, val updated: Int, val failed: Int) : LibraryUpdateStatus
    data class Error(val message: String?) : LibraryUpdateStatus
}

/**
 * WorkManager/Ktor messages may embed a full URL (chapter path, query, tokens).
 * Keep the host for diagnosis and drop everything after it.
 */
internal fun updateErrorHost(message: String?): String =
    message
        ?.let { Regex("""https?://([^/\s?#]+)""").find(it)?.groupValues?.getOrNull(1) }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

/**
 * Owns the unique library-update work. `on launch` is a one-time request,
 * every other interval is a periodic request; the persisted settings decide
 * the constraints. Safe to call after every settings save.
 */
object LibraryUpdateScheduler {
    private const val BackoffMinutes = 30L

    private val lock = Any()
    private var lastAppliedKey: String? = null
    private val _status = MutableStateFlow<LibraryUpdateStatus>(LibraryUpdateStatus.Idle)
    val status: StateFlow<LibraryUpdateStatus> = _status.asStateFlow()

    fun sync(context: Context, settings: PersistedSettings): LibraryUpdateStatus {
        if (!settings.libraryAutoUpdate) {
            cancel(context)
            _status.value = LibraryUpdateStatus.Disabled
            return LibraryUpdateStatus.Disabled
        }
        val spec = LibraryUpdateSchedule.spec(settings)
        val key = "${spec.oneTime}|${spec.intervalMinutes}|${spec.flexMinutes}|" +
            "${spec.networkType}|${spec.requiresCharging}|${spec.requiresBatteryNotLow}"
        synchronized(lock) {
            if (key == lastAppliedKey) return _status.value
            lastAppliedKey = key
        }
        return try {
            val manager = WorkManager.getInstance(context)
            if (spec.oneTime) {
                // Switching interval modes must remove the other unique work,
                // otherwise a stale periodic/one-time request survives.
                manager.cancelUniqueWork(LibraryUpdateSchedule.UniquePeriodicWork)
                manager.enqueueUniqueWork(
                    LibraryUpdateSchedule.UniqueLaunchWork,
                    ExistingWorkPolicy.REPLACE,
                    buildOneTimeRequest(spec),
                )
            } else {
                manager.cancelUniqueWork(LibraryUpdateSchedule.UniqueLaunchWork)
                manager.enqueueUniquePeriodicWork(
                    LibraryUpdateSchedule.UniquePeriodicWork,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    buildPeriodicRequest(spec),
                )
            }
            _status.value = LibraryUpdateStatus.Scheduled
            MiruLog.i(
                "updates",
                "library schedule ${if (spec.oneTime) "on-launch" else "${spec.intervalMinutes}m"} " +
                    "network=${spec.networkType} charging=${spec.requiresCharging}",
            )
            LibraryUpdateStatus.Scheduled
        } catch (e: Exception) {
            // Do not claim the schedule exists when WorkManager rejected it.
            synchronized(lock) { lastAppliedKey = null }
            _status.value = LibraryUpdateStatus.Error(e.message)
            // WorkManager/Ktor messages may embed full URLs: log host+class only.
            MiruLog.w(
                "updates",
                "library schedule failed host=${updateErrorHost(e.message)} " +
                    "class=${e::class.simpleName}",
            )
            LibraryUpdateStatus.Error(e.message)
        }
    }

    fun cancel(context: Context) {
        synchronized(lock) { lastAppliedKey = null }
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LibraryUpdateSchedule.UniquePeriodicWork)
                cancelUniqueWork(LibraryUpdateSchedule.UniqueLaunchWork)
            }
        }
    }

    fun recordRun(summary: UpdateCheckSummary) {
        _status.value = LibraryUpdateStatus.LastRun(
            checked = summary.checked,
            updated = summary.updated,
            failed = summary.failed,
        )
    }

    fun recordFailure(message: String?) {
        _status.value = LibraryUpdateStatus.Error(message)
    }

    internal fun buildPeriodicRequest(spec: LibraryUpdateScheduleSpec): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<LibraryUpdateWorker>(
            spec.intervalMinutes,
            TimeUnit.MINUTES,
            spec.flexMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints(spec))
            .setInitialDelay(spec.intervalMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()

    internal fun buildOneTimeRequest(spec: LibraryUpdateScheduleSpec): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
            .setConstraints(constraints(spec))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()

    private fun constraints(spec: LibraryUpdateScheduleSpec): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(spec.networkType)
            .setRequiresCharging(spec.requiresCharging)
            .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
            .build()
}
