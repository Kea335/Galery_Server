package com.kadr.app.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.kadr.app.MainActivity
import com.kadr.app.R
import com.kadr.app.data.repo.BackupPhase
import com.kadr.app.data.repo.BackupProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backup notification (§10.6). Low importance and silent on purpose — it is
 * ambient feedback, not an interruption (§12).
 */
@Singleton
class BackupNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.backup_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.backup_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun progressNotification(progress: BackupProgress?, workId: java.util.UUID?): Notification {
        val builder = baseBuilder()
            .setContentTitle(titleFor(progress))
            .setContentText(detailFor(progress))
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (progress != null && progress.total > 0) {
            builder.setProgress(progress.total, progress.done, progress.phase != BackupPhase.UPLOADING)
        } else {
            builder.setProgress(0, 0, true)
        }

        workId?.let { id ->
            builder.addAction(
                0,
                context.getString(R.string.backup_action_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
        }

        return builder.build()
    }

    fun completionNotification(summary: String) {
        // From API 33 the user can decline notifications outright. Checked
        // rather than attempted, because a summary of a batch that has already
        // finished is not worth throwing over — and below 33 the permission does
        // not exist for checkSelfPermission to find, so the guard is skipped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.backup_done_title))
            .setContentText(summary)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(DONE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // The permission can be revoked between the check above and here.
            Log.w(TAG, "could not post the completion notification", e)
        }
    }

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .setContentIntent(openAppIntent())

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun titleFor(progress: BackupProgress?): String = when (progress?.phase) {
        null, BackupPhase.IDLE -> context.getString(R.string.backup_starting)
        BackupPhase.SCANNING -> context.getString(R.string.backup_scanning)
        BackupPhase.HASHING -> context.getString(R.string.backup_hashing)
        BackupPhase.CHECKING -> context.getString(R.string.backup_checking)
        BackupPhase.UPLOADING -> context.getString(
            R.string.backup_uploading_count,
            (progress.done + 1).coerceAtMost(progress.total.coerceAtLeast(1)),
            progress.total.coerceAtLeast(1),
        )
    }

    /** "IMG_2841.jpg · 4.2 MB/s" — the shape §10.6 asks for. */
    private fun detailFor(progress: BackupProgress?): String {
        if (progress == null) return ""
        val parts = buildList {
            progress.filename?.let(::add)
            if (progress.bytesPerSecond > 0) add(formatRate(progress.bytesPerSecond))
        }
        return parts.joinToString(" · ")
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val mb = bytesPerSecond / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        }
    }

    companion object {
        private const val TAG = "KadrNotifications"
        const val CHANNEL_ID = "kadr_backup"
        const val PROGRESS_NOTIFICATION_ID = 4201
        const val DONE_NOTIFICATION_ID = 4202
    }
}
