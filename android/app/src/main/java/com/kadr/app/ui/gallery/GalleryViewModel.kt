package com.kadr.app.ui.gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.kadr.app.backup.BackupScheduler
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.repo.BackupProgress
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.data.repo.FreeUpPlan
import com.kadr.app.data.repo.LibraryRepository
import com.kadr.app.data.repo.ServerFull
import com.kadr.app.data.repo.SpaceRepository
import com.kadr.app.data.video.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kadr.app.R
import android.content.Context

/** A timeline row: either a month divider or a photo cell. */
sealed interface TimelineEntry {
    data class MonthHeader(val month: YearMonth, val label: String) : TimelineEntry
    data class Photo(val item: GalleryItem) : TimelineEntry
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val backup: BackupRepository,
    private val space: SpaceRepository,
    private val scheduler: BackupScheduler,
    /** Handed to the screens so they can build their own short-lived players. */
    val playerFactory: PlayerFactory,
    settingsStore: SettingsStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * The grid: photos with month dividers folded in, a page at a time.
     *
     * `cachedIn` is what keeps a rotation — or a trip into the viewer and back —
     * from throwing away every page and reading them all again.
     */
    val entries: Flow<PagingData<TimelineEntry>> = library.timelinePages()
        .map { page ->
            page.map<GalleryItem, TimelineEntry>(TimelineEntry::Photo)
                .insertSeparators { before, after -> monthHeaderBetween(before, after) }
        }
        .cachedIn(viewModelScope)

    /** Flat, newest first — the order the viewer pages through. */
    val photos: Flow<PagingData<GalleryItem>> = library.viewerPages()
        .cachedIn(viewModelScope)

    /** For the header line; counting rows is far cheaper than loading them. */
    val photoCount: StateFlow<Int> = library.observeTimelineCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val settings: StateFlow<KadrSettings> = settingsStore.settings
    val syncing: StateFlow<Boolean> = library.syncing
    val backingUp: StateFlow<Boolean> = backup.running
    val progress: StateFlow<BackupProgress?> = backup.progress
    val serverFull: StateFlow<ServerFull?> = backup.serverFull

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ─── Selection (§12) ────────────────────────────────────────────────────

    /**
     * Item keys, not items: the grid is paged, so the same photo arrives as a
     * fresh object every time its page is re-read. The key is what survives.
     *
     * Selection mode is on exactly while this is non-empty — there is no second
     * flag to leave stranded, and unpicking the last photo puts the timeline
     * back the way it was.
     */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _freeUpPlan = MutableStateFlow<FreeUpPlan?>(null)
    val freeUpPlan: StateFlow<FreeUpPlan?> = _freeUpPlan.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun toggleSelection(item: GalleryItem) {
        _selection.update { current ->
            if (item.key in current) current - item.key else current + item.key
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** §12: pinch to move between 2, 3 and 5 columns. */
    var columns by mutableIntStateOf(3)
        private set

    init {
        refresh()
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** One step wider cells (fewer columns) or narrower, as the pinch dictates. */
    fun stepColumns(zoomingIn: Boolean) {
        val index = COLUMN_STEPS.indexOf(columns).takeIf { it >= 0 } ?: 1
        val next = if (zoomingIn) index - 1 else index + 1
        columns = COLUMN_STEPS[next.coerceIn(0, COLUMN_STEPS.lastIndex)]
    }

    fun refresh() {
        viewModelScope.launch {
            library.sync().onFailure { _message.value = context.getString(R.string.timeline_msg_sync_failed, it.message.orEmpty()) }
        }
    }

    fun backupNow() {
        scheduler.backupNow()
        _message.value = context.getString(R.string.timeline_msg_backup_queued)
    }

    // ─── Free up space, for the selection (§10.7, §12) ──────────────────────

    /**
     * Builds a plan for the picked photos. Every rule §10.7 sets still applies —
     * the repository re-asks the server whether it holds those exact hashes
     * before anything is offered up for deletion.
     */
    fun prepareFreeUp() {
        if (_busy.value) return
        val ids = _selection.value.mapNotNull(::localAssetId)
        if (ids.isEmpty()) {
            _message.value = context.getString(R.string.timeline_msg_nothing_backed_up)
            return
        }

        viewModelScope.launch {
            _busy.value = true
            space.plan(ids)
                .onSuccess { plan ->
                    if (plan.isEmpty) {
                        _message.value = context.getString(R.string.timeline_msg_no_vouch)
                    } else {
                        _freeUpPlan.value = plan
                    }
                }
                .onFailure { _message.value = context.getString(R.string.timeline_msg_check_failed, it.message.orEmpty()) }
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
            clearSelection()
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
            clearSelection()
            _message.value =
                context.resources.getQuantityString(R.plurals.common_freed_files, freed, freed)
        }
    }

    /**
     * Local row id out of a timeline key. Server-only rows start with `r` and
     * have no local file to free, so they drop out here.
     */
    private fun localAssetId(key: String): Long? =
        key.takeIf { it.startsWith("l") }?.drop(1)?.toLongOrNull()

    /**
     * Where a photo sits in the timeline, asked of the database rather than of
     * the loaded pages — the viewer can be opened on a photo whose neighbours
     * have never been read.
     */
    suspend fun positionOf(key: String, capturedAt: Long): Int =
        library.positionOf(key, capturedAt)

    /** What Coil should load for a cell: the local file if we still have it. */
    fun thumbnailModel(item: GalleryItem): Any? =
        item.localUri ?: item.remoteId?.let(library::thumbnailUrl)

    /** Full-size source for the viewer. */
    fun fullSizeModel(item: GalleryItem): Any? = mediaUri(item)

    /**
     * Where the actual bytes live: the device for a local file, the server's
     * Range-capable `/file` endpoint otherwise (§9, §11).
     */
    fun mediaUri(item: GalleryItem): String? =
        item.localUri ?: item.remoteId?.let(library::fileUrl)

    /**
     * A divider goes between two photos when the month changes, and above the
     * first photo of all. `after == null` is the end of the list, which needs no
     * divider — the months are already behind us.
     */
    private fun monthHeaderBetween(
        before: TimelineEntry?,
        after: TimelineEntry?,
    ): TimelineEntry.MonthHeader? {
        val next = (after as? TimelineEntry.Photo)?.item ?: return null
        val nextMonth = next.month()

        val previous = (before as? TimelineEntry.Photo)?.item
        if (previous != null && previous.month() == nextMonth) return null

        // Drop the year for the current one — a magazine does not repeat it on
        // every spread either.
        val zone = ZoneId.systemDefault()
        val pattern = if (nextMonth.year == YearMonth.now(zone).year) MONTH_ONLY else MONTH_AND_YEAR
        return TimelineEntry.MonthHeader(nextMonth, pattern.format(nextMonth))
    }

    private fun GalleryItem.month(): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()))

    private companion object {
        val COLUMN_STEPS = listOf(2, 3, 5)
        val MONTH_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", Locale.getDefault())
        val MONTH_AND_YEAR: DateTimeFormatter =
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    }
}
