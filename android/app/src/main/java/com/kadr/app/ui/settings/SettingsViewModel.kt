package com.kadr.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.backup.BackupScheduler
import com.kadr.app.data.media.MediaStoreScanner
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.HealthResponse
import com.kadr.app.data.remote.TrashedAssetDto
import com.kadr.app.data.repo.AlbumRepository
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.data.repo.FreeUpPlan
import com.kadr.app.data.repo.LibraryRepository
import com.kadr.app.data.repo.SpaceRepository
import com.kadr.app.data.video.VideoCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kadr.app.R
import android.content.Context

data class TrashUiState(
    val loading: Boolean = false,
    val items: List<TrashedAssetDto> = emptyList(),
    val retentionDays: Int = 30,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val library: LibraryRepository,
    private val albums: AlbumRepository,
    private val backup: BackupRepository,
    private val space: SpaceRepository,
    private val scheduler: BackupScheduler,
    private val videoCache: VideoCache,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val settings: StateFlow<KadrSettings> = settingsStore.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _health = MutableStateFlow<HealthResponse?>(null)
    val health: StateFlow<HealthResponse?> = _health.asStateFlow()

    private val _trash = MutableStateFlow(TrashUiState())
    val trash: StateFlow<TrashUiState> = _trash.asStateFlow()

    private val _freeUpPlan = MutableStateFlow<FreeUpPlan?>(null)
    val freeUpPlan: StateFlow<FreeUpPlan?> = _freeUpPlan.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refreshHealth()
        refreshCacheSize()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun refreshHealth() {
        if (!settingsStore.current.isPaired) return
        viewModelScope.launch {
            library.health().onSuccess { _health.value = it }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheBytes.value = videoCache.usedBytes() }
    }

    fun clearMediaCache() {
        viewModelScope.launch {
            videoCache.clear()
            _cacheBytes.value = videoCache.usedBytes()
            _message.value = context.getString(R.string.settings_msg_cache_cleared)
        }
    }

    // ─── Toggles ────────────────────────────────────────────────────────────

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
        // Turning the rule back on has to bring what it parked back with it —
        // nothing else ever moves a row out of SKIPPED.
        if (enabled) viewModelScope.launch { backup.requeueSkipped() }
    }

    // ─── Unpairing ──────────────────────────────────────────────────────────

    /**
     * Signs this device out and forgets the server it was signed in to.
     *
     * The screen only navigated to the login form before, which left the token,
     * the mirrored library and the scheduled batch exactly where they were: the
     * app looked signed out and carried on backing up.
     *
     * [onDone] runs after the credentials are gone rather than before, so the
     * login screen cannot be shown over a device that is still paired.
     */
    fun unpair(onDone: () -> Unit) {
        viewModelScope.launch {
            library.forgetServer()
            albums.clearCache()
            // Before stopping: the scheduler re-arms itself, and only an
            // unpaired device makes it cancel instead.
            settingsStore.clearPairing()
            scheduler.stop()
            _health.value = null
            _trash.value = TrashUiState()
            onDone()
        }
    }

    fun setDynamicColor(enabled: Boolean) = settingsStore.setDynamicColor(enabled)

    fun setVideoCacheMb(value: Int) = settingsStore.setVideoCacheMb(value)

    fun toggleExcludedFolder(folder: String) {
        val current = settingsStore.current.excludedFolders
        settingsStore.saveExcludedFolders(
            if (folder in current) current - folder else current + folder,
        )
    }

    val knownFolders: List<String> = MediaStoreScanner.DEFAULT_EXCLUDED_FOLDERS.toList()

    // ─── Trash ──────────────────────────────────────────────────────────────

    fun loadTrash() {
        viewModelScope.launch {
            _trash.update { it.copy(loading = true, error = null) }
            library.trash()
                .onSuccess { page ->
                    _trash.value = TrashUiState(
                        loading = false,
                        items = page.assets,
                        retentionDays = page.retentionDays,
                    )
                }
                .onFailure { error ->
                    _trash.update { it.copy(loading = false, error = error.message) }
                }
        }
    }

    fun restore(assetId: String) {
        viewModelScope.launch {
            library.restore(assetId)
                .onSuccess {
                    _message.value = context.getString(R.string.settings_msg_restored)
                    loadTrash()
                    library.sync()
                }
                .onFailure { _message.value = context.getString(R.string.settings_msg_restore_failed, it.message.orEmpty()) }
        }
    }

    // ─── Free up space (§10.7) ──────────────────────────────────────────────

    /** Builds the plan and re-confirms every hash with the server first. */
    fun prepareFreeUp() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            space.plan()
                .onSuccess { plan ->
                    if (plan.isEmpty) {
                        _message.value = context.getString(R.string.settings_msg_nothing_safe)
                    } else {
                        _freeUpPlan.value = plan
                    }
                }
                .onFailure { _message.value = context.getString(R.string.settings_msg_check_failed, it.message.orEmpty()) }
            _busy.value = false
        }
    }

    fun cancelFreeUp() {
        _freeUpPlan.value = null
    }

    fun deleteRequestFor(plan: FreeUpPlan) = space.deleteRequest(plan)

    /** Called after the system dialog closes, whatever the user chose there. */
    fun onFreeUpFinished(plan: FreeUpPlan) {
        viewModelScope.launch {
            val freed = space.markFreed(plan)
            _freeUpPlan.value = null
            _message.value = if (freed > 0) {
                context.resources.getQuantityString(R.plurals.common_freed_files, freed, freed)
            } else {
                context.getString(R.string.common_nothing_removed)
            }
        }
    }

    /** API 26–29 has no system dialog; the app's own confirmation is the gate. */
    fun deleteWithoutSystemDialog(plan: FreeUpPlan) {
        viewModelScope.launch {
            _busy.value = true
            space.deleteDirectly(plan)
            val freed = space.markFreed(plan)
            _freeUpPlan.value = null
            _busy.value = false
            _message.value =
                context.resources.getQuantityString(R.plurals.common_freed_files, freed, freed)
        }
    }
}
