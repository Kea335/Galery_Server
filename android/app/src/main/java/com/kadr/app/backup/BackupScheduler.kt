package com.kadr.app.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kadr.app.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Brings the scheduled job in line with the current settings. Safe to call
     * on every app start and after any toggle — WorkManager keeps the schedule
     * across reboots by itself.
     */
    fun sync(): Operation {
        if (!settings.current.autoBackup || !settings.current.isPaired) {
            return workManager.cancelUniqueWork(PERIODIC_WORK)
        }

        val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_BACKUP)
            .build()

        return workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** §10.5 — "Back up now" bypasses the constraints once. */
    fun backupNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_BACKUP)
            .build()

        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Picks an interrupted batch back up after a reboot or an app update.
     * Unlike [backupNow] this keeps the user's constraints — a restart is not
     * consent to spend mobile data.
     */
    fun resumeAfterBoot(): Operation {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints())
            // Deliberately NOT expedited. WorkManager runs expedited work
            // in-process through GreedyScheduler, so a request enqueued from a
            // boot receiver dies with that receiver's process. A plain request
            // is handed to JobScheduler and outlives it — which is the whole
            // point here.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_BACKUP)
            .build()

        // REPLACE, not KEEP: a stale resume request left over from an earlier
        // boot would otherwise swallow every future one silently.
        return workManager.enqueueUniqueWork(RESUME_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Stops whatever is running now.
     *
     * WorkManager has no way to cancel one occurrence of a periodic job, so
     * stopping a batch that came from the schedule means cancelling the schedule
     * — and [sync] then puts it straight back. Without that, one tap on "Stop"
     * would quietly mean "never automatically again", which is not what the
     * button says. When the device is no longer paired [sync] cancels instead,
     * so unpairing still leaves nothing behind.
     */
    fun stop() {
        workManager.cancelUniqueWork(NOW_WORK)
        workManager.cancelUniqueWork(RESUME_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        sync()
    }

    fun observeWork(): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(TAG_BACKUP)

    private fun constraints(): Constraints {
        val prefs = settings.current
        return Constraints.Builder()
            // §10.5 defaults: unmetered network and a battery that is not low.
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(prefs.chargingOnly)
            .build()
    }

    private companion object {
        const val PERIODIC_WORK = "kadr-backup-periodic"
        const val NOW_WORK = "kadr-backup-now"
        const val RESUME_WORK = "kadr-backup-resume"
        const val TAG_BACKUP = "kadr-backup"
    }
}
