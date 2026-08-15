package com.kadr.app.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kadr.app.data.prefs.SettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * WorkManager reschedules its periodic job across a reboot, but that job stays
 * pinned to its next window — after a restart mid-batch it can sit idle for
 * hours. A batch that was interrupted should carry on promptly instead.
 *
 * The resume request keeps the user's network and battery rules; only an
 * explicit "Back up now" is allowed to ignore them (§10.5).
 *
 * Dependencies come from an EntryPoint rather than @AndroidEntryPoint: Hilt's
 * generated receiver base does not override onReceive, so the usual
 * `super.onReceive()` call does not compile.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootDependencies {
        fun scheduler(): BackupScheduler
        fun settingsStore(): SettingsStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        // WorkManager writes its queue on a background executor. Without
        // goAsync the process can be torn down the moment onReceive returns and
        // the enqueue never lands — which looks exactly like the boot handler
        // never running.
        val pending = goAsync()

        Thread {
            try {
                val deps = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BootDependencies::class.java,
                )

                val settings = deps.settingsStore().current
                if (!settings.isPaired || !settings.autoBackup) return@Thread

                Log.i(TAG, "device restarted — restoring the backup schedule")
                val scheduler = deps.scheduler()
                scheduler.sync().result.get()
                scheduler.resumeAfterBoot().result.get()
                Log.i(TAG, "backup queued after restart")
            } catch (e: Exception) {
                Log.e(TAG, "could not restore the backup schedule", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "KadrBoot"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // An app update also drops any in-flight work.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
