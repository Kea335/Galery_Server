package com.kadr.app.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kadr.app.data.repo.BackupOutcome
import com.kadr.app.data.repo.BackupProgress
import com.kadr.app.data.repo.BackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The batch loop (§10). Runs in the foreground so a long upload survives the
 * screen going off, and resumes from Room on every start — reboot, force-stop
 * or a week of server downtime make no difference to it.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: BackupRepository,
    private val notifications: BackupNotifications,
    private val settings: com.kadr.app.data.prefs.SettingsStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(repository.progress.value)

    override suspend fun doWork(): Result = coroutineScope {
        // Expedited runs (a boot resume) cannot carry a battery constraint, so
        // §10.5's battery rule is enforced here instead of by JobScheduler.
        if (!batteryAllowsBackup()) {
            Log.i(TAG, "battery rules say not now — will try again later")
            return@coroutineScope Result.retry()
        }

        notifications.ensureChannel()
        runCatching { setForeground(foregroundInfo(null)) }

        // Repaint the notification as work moves, but not on every chunk —
        // NotificationManager throttles callers that post too often.
        val painter = launch {
            var lastPaintedAt = 0L
            repository.progress.collect { progress ->
                val now = System.currentTimeMillis()
                if (progress != null && now - lastPaintedAt >= NOTIFICATION_INTERVAL_MS) {
                    lastPaintedAt = now
                    runCatching { setForeground(foregroundInfo(progress)) }
                }
            }
        }

        val result = repository.runBackup { isStopped }
        painter.cancel()

        result.fold(
            onSuccess = { outcome -> finish(outcome) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "backup run failed", error)
                // Not paired, or something equally unfixable by waiting.
                if (error is IllegalStateException) return@fold Result.failure()
                if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
            },
        )
    }

    private fun finish(outcome: BackupOutcome): Result {
        // A full server is not something retrying fixes, and hammering it would
        // just burn battery. Say so and stop until the owner makes room.
        if (outcome.serverFull) {
            notifications.completionNotification(
                "The server is out of space — ${outcome.remaining} files are waiting.",
            )
            return Result.success()
        }

        if (outcome.stoppedEarly) {
            Log.i(TAG, "backup stopped early with ${outcome.remaining} left")
            return Result.success()
        }

        if (outcome.didWork || outcome.failed > 0) {
            notifications.completionNotification(summarise(outcome))
        }

        // Something transient went wrong but progress is still possible — let
        // WorkManager's own backoff space out the next attempt.
        return if (outcome.failed > 0 && outcome.remaining > 0 && runAttemptCount < MAX_RUN_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun summarise(outcome: BackupOutcome): String = buildList {
        if (outcome.uploaded > 0) add("${outcome.uploaded} uploaded")
        if (outcome.deduped > 0) add("${outcome.deduped} already on the server")
        if (outcome.skipped > 0) add("${outcome.skipped} skipped")
        if (outcome.failed > 0) add("${outcome.failed} failed")
        if (isEmpty()) add("Nothing to do")
    }.joinToString(" · ")

    private fun batteryAllowsBackup(): Boolean {
        val manager = applicationContext.getSystemService(BatteryManager::class.java)
            ?: return true
        val charging = manager.isCharging
        if (settings.current.chargingOnly && !charging) return false
        if (charging) return true

        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // getIntProperty returns Integer.MIN_VALUE when it has no idea.
        return level <= 0 || level > LOW_BATTERY_PERCENT
    }

    private fun foregroundInfo(progress: BackupProgress?): ForegroundInfo {
        val notification = notifications.progressNotification(progress, id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                BackupNotifications.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(BackupNotifications.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "KadrWorker"
        private const val NOTIFICATION_INTERVAL_MS = 800L
        private const val MAX_RUN_ATTEMPTS = 5

        /** Matches what JobScheduler treats as "battery not low". */
        private const val LOW_BATTERY_PERCENT = 15
    }
}
