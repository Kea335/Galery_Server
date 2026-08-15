package com.kadr.app.ui.gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.backup.BackupScheduler
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.repo.BackupProgress
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.data.repo.LibraryRepository
import com.kadr.app.data.video.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** A timeline row: either a month divider or a photo cell. */
sealed interface TimelineEntry {
    data class MonthHeader(val month: YearMonth, val label: String) : TimelineEntry
    data class Photo(val item: GalleryItem) : TimelineEntry
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val backup: BackupRepository,
    private val scheduler: BackupScheduler,
    /** Handed to the screens so they can build their own short-lived players. */
    val playerFactory: PlayerFactory,
    settingsStore: SettingsStore,
) : ViewModel() {

    /** Flat, newest first — the order the viewer pages through. */
    val photos: StateFlow<List<GalleryItem>> = library.observeTimeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same list with month dividers folded in. */
    val entries: StateFlow<List<TimelineEntry>> = library.observeTimeline()
        .map(::withMonthHeaders)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<KadrSettings> = settingsStore.settings
    val syncing: StateFlow<Boolean> = library.syncing
    val backingUp: StateFlow<Boolean> = backup.running
    val progress: StateFlow<BackupProgress?> = backup.progress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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
            library.sync().onFailure { _message.value = "Sync failed: ${it.message}" }
        }
    }

    fun backupNow() {
        scheduler.backupNow()
        _message.value = "Backup queued."
    }

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

    private fun withMonthHeaders(items: List<GalleryItem>): List<TimelineEntry> {
        if (items.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val thisYear = YearMonth.now(zone).year
        val out = ArrayList<TimelineEntry>(items.size + 16)
        var current: YearMonth? = null

        for (item in items) {
            val month = YearMonth.from(Instant.ofEpochMilli(item.capturedAt).atZone(zone))
            if (month != current) {
                // Drop the year for the current one — a magazine does not repeat
                // it on every spread either.
                val pattern = if (month.year == thisYear) MONTH_ONLY else MONTH_AND_YEAR
                out += TimelineEntry.MonthHeader(month, pattern.format(month))
                current = month
            }
            out += TimelineEntry.Photo(item)
        }
        return out
    }

    private companion object {
        val COLUMN_STEPS = listOf(2, 3, 5)
        val MONTH_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", Locale.getDefault())
        val MONTH_AND_YEAR: DateTimeFormatter =
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    }
}
