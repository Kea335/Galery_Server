package com.kadr.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.backup.BackupScheduler
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.StateCount
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.repo.AlbumRepository
import com.kadr.app.data.repo.BackupProgress
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.data.repo.LibraryRepository
import com.kadr.app.ui.readable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kadr.app.R
import android.content.Context

data class StorageSummary(
    val reclaimableBytes: Long,
    val alreadyFreedBytes: Long,
    val serverFreeBytes: Long?,
    val serverAssets: Int,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: BackupRepository,
    private val library: LibraryRepository,
    private val albums: AlbumRepository,
    private val settingsStore: SettingsStore,
    private val scheduler: BackupScheduler,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val assets: StateFlow<List<LocalAsset>> = repository.observeAssets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val counts: StateFlow<List<StateCount>> = repository.observeStateCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val failures: StateFlow<List<LocalAsset>> = repository.observeFailed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<KadrSettings> = settingsStore.settings
    val progress: StateFlow<BackupProgress?> = repository.progress
    val running: StateFlow<Boolean> = repository.running
    val scanning: StateFlow<Boolean> = repository.scanning
    val scanProgress: StateFlow<Int> = repository.scanProgress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _storage = MutableStateFlow<StorageSummary?>(null)
    val storage: StateFlow<StorageSummary?> = _storage.asStateFlow()

    init {
        // Keeps the periodic job in step with whatever the settings say now.
        scheduler.sync()
        refreshStorage()
    }

    /** §12 screen 4 wants storage used and the server's free space on screen. */
    fun refreshStorage() {
        viewModelScope.launch {
            val reclaimable = repository.reclaimableBytes()
            val freed = repository.freedBytes()
            val health = library.health().getOrNull()
            _storage.value = StorageSummary(
                reclaimableBytes = reclaimable,
                alreadyFreedBytes = freed,
                serverFreeBytes = health?.freeDiskBytes,
                serverAssets = health?.assetCount ?: 0,
            )
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun scan() = guarded {
        repository.scan()
            .onSuccess { result ->
                _message.value = context.getString(
                    R.string.backup_msg_scanned,
                    result.total,
                    result.added,
                    result.changed,
                    result.unchanged,
                )
            }
            .onFailure { _message.value = context.getString(R.string.backup_msg_scan_failed, it.readable()) }
    }

    /** Hands the batch to WorkManager so it survives the app being closed. */
    fun backupNow() {
        scheduler.backupNow()
        _message.value = context.getString(R.string.backup_msg_queued)
    }

    fun stopBackup() {
        scheduler.stop()
        _message.value = context.getString(R.string.backup_msg_stopping)
    }

    fun retryFailed() = guarded {
        val reset = repository.retryFailed()
        _message.value = if (reset > 0) {
            context.getString(R.string.backup_msg_retry_queued, reset)
        } else {
            context.getString(R.string.backup_msg_nothing_failed)
        }
        if (reset > 0) scheduler.backupNow()
    }

    fun upload(assetId: Long) = guarded {
        repository.upload(assetId)
            .onSuccess { _message.value = context.getString(R.string.backup_msg_uploaded, it) }
            .onFailure { _message.value = context.getString(R.string.backup_msg_upload_failed, it.readable()) }
    }

    fun setAutoBackup(enabled: Boolean) {
        settingsStore.setAutoBackup(enabled)
        scheduler.sync()
    }

    fun setWifiOnly(enabled: Boolean) {
        settingsStore.setWifiOnly(enabled)
        scheduler.sync()
    }

    fun setChargingOnly(enabled: Boolean) {
        settingsStore.setChargingOnly(enabled)
        scheduler.sync()
    }

    fun setIncludeVideos(enabled: Boolean) {
        settingsStore.setIncludeVideos(enabled)
        // Turning the rule back on has to bring what it parked back with it.
        if (enabled) viewModelScope.launch { repository.requeueSkipped() }
    }

    /**
     * The same unpairing [com.kadr.app.ui.settings.SettingsViewModel.unpair]
     * performs: the mirror of the old server goes too, or its photos stay in
     * the timeline of the next one.
     *
     * Order matters — the token goes before the scheduler is stopped, because
     * the scheduler re-arms itself and only an unpaired device makes it cancel.
     */
    fun unpair() {
        viewModelScope.launch {
            library.forgetServer()
            albums.clearCache()
            settingsStore.clearPairing()
            scheduler.stop()
        }
    }

    private fun guarded(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
