package dev.mirutime.app.features.settings.backup

import dev.mirutime.app.features.settings.data.RestoreProgress
import dev.mirutime.app.features.settings.data.RestoreStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackupOperationKind { Backup, Restore }

/**
 * One observable view of the foreground backup/restore service.
 *
 * The service is the only writer; the settings screen only reads it. Keeping
 * this process-wide (instead of a Compose-owned state) means navigation does
 * not lose progress and the raw backup work never runs in a screen scope.
 * A process death resets the state, which matches START_NOT_STICKY.
 */
data class BackupOperationState(
    val kind: BackupOperationKind? = null,
    val running: Boolean = false,
    val stage: RestoreStage? = null,
    val current: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    val success: Boolean? = null,
    val message: String? = null,
    val finishedAt: Long = 0L,
) {
    val finished: Boolean get() = !running && success != null
}

object BackupOperationMonitor {
    private val mutableState = MutableStateFlow(BackupOperationState())
    val state: StateFlow<BackupOperationState> = mutableState.asStateFlow()

    fun begin(kind: BackupOperationKind) {
        mutableState.value = BackupOperationState(kind = kind, running = true)
    }

    fun backupProgress(fraction: Float) {
        val current = mutableState.value
        if (!current.running || current.kind != BackupOperationKind.Backup) return
        mutableState.value = current.copy(
            percent = (fraction.coerceIn(0f, 1f) * 100).toInt().coerceIn(0, 100),
        )
    }

    fun restoreProgress(progress: RestoreProgress) {
        val current = mutableState.value
        if (!current.running || current.kind != BackupOperationKind.Restore) return
        mutableState.value = current.copy(
            stage = progress.stage,
            current = progress.current,
            total = progress.total,
            percent = BackupNotificationContentFactory.restore(progress).percent,
        )
    }

    fun finish(
        kind: BackupOperationKind,
        success: Boolean,
        message: String?,
    ) {
        val current = mutableState.value
        mutableState.value = current.copy(
            kind = kind,
            running = false,
            success = success,
            message = message,
            percent = if (success) 100 else current.percent,
            finishedAt = System.currentTimeMillis(),
        )
    }

    fun clear() {
        mutableState.value = BackupOperationState()
    }
}
