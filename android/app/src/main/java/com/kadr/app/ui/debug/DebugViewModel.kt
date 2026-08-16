package com.kadr.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.backup.BackupScheduler
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.StateCount
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
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
    private val settingsStore: SettingsStore,
    private val scheduler: BackupScheduler,
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
                _message.value =
                    "Scanned ${result.total}: ${result.added} new, ${result.changed} changed, ${result.unchanged} unchanged."
            }
            .onFailure { _message.value = "Scan failed: ${it.readable()}" }
    }

    /** Hands the batch to WorkManager so it survives the app being closed. */
    fun backupNow() {
        scheduler.backupNow()
        _message.value = "Backup queued."
    }

    fun stopBackup() {
        scheduler.stop()
        _message.value = "Stopping after the current file."
    }

    fun retryFailed() = guarded {
        val reset = repository.retryFailed()
        _message.value = if (reset > 0) "$reset failures queued again." else "Nothing failed."
        if (reset > 0) scheduler.backupNow()
    }

    fun upload(assetId: Long) = guarded {
        repository.upload(assetId)
            .onSuccess { _message.value = "Uploaded. Server asset id $it" }
            .onFailure { _message.value = "Upload failed: ${it.readable()}" }
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
    }

    fun unpair() {
        scheduler.stop()
        settingsStore.clearPairing()
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
