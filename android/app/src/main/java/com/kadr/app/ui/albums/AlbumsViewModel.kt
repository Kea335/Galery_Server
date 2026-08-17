package com.kadr.app.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.kadr.app.data.local.AlbumSummary
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.repo.AlbumRepository
import com.kadr.app.data.repo.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val albums: AlbumRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    val list: StateFlow<List<AlbumSummary>> = albums.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            albums.sync().onFailure { _message.value = "Could not read albums: ${it.message}" }
        }
    }

    fun create(name: String) = guarded {
        albums.create(name)
            .onSuccess { _message.value = "Album created." }
            .onFailure { _message.value = "Could not create it: ${it.message}" }
    }

    fun rename(id: String, name: String) = guarded {
        albums.rename(id, name)
            .onFailure { _message.value = "Could not rename it: ${it.message}" }
    }

    fun delete(id: String) = guarded {
        albums.delete(id)
            .onSuccess { _message.value = "Album deleted. The photos are untouched." }
            .onFailure { _message.value = "Could not delete it: ${it.message}" }
    }

    /**
     * Adds the picked photos to an album, then says plainly what happened.
     *
     * A photo waiting to be backed up cannot be in an album — the album lives on
     * the server, and the server has never seen that file. Reporting the number
     * is the difference between an explanation and a mystery.
     */
    fun addToAlbum(albumId: String, keys: Set<String>, onDone: () -> Unit) = guarded {
        albums.addByKeys(albumId, keys)
            .onSuccess { result ->
                _message.value = when {
                    result.added == 0 ->
                        "Nothing was added — these are still waiting to be backed up."

                    result.notBackedUp > 0 ->
                        "${result.added} added. ${result.notBackedUp} are still waiting to be " +
                            "backed up, so they could not go in yet."

                    else -> "${result.added} added."
                }
                onDone()
            }
            .onFailure { _message.value = "Could not add them: ${it.message}" }
    }

    /** Contents of one album, paged, in the timeline's own order. */
    fun pages(albumId: String): Flow<PagingData<GalleryItem>> =
        albums.albumPages(albumId).cachedIn(viewModelScope)

    fun observeCount(albumId: String): Flow<Int> = albums.observeAlbumCount(albumId)

    /** The album again, for the full-screen viewer opened from inside it. */
    fun viewerPages(albumId: String): Flow<PagingData<GalleryItem>> =
        albums.albumViewerPages(albumId).cachedIn(viewModelScope)

    suspend fun positionInAlbum(albumId: String, key: String, capturedAt: Long): Int =
        albums.positionInAlbum(albumId, key, capturedAt)

    fun setCover(albumId: String, item: GalleryItem) = guarded {
        val assetId = item.remoteId
        if (assetId == null) {
            _message.value = "That photo is not on the server yet, so it cannot be the cover."
            return@guarded
        }
        albums.setCover(albumId, assetId)
            .onSuccess { _message.value = "Cover set." }
            .onFailure { _message.value = "Could not set the cover: ${it.message}" }
    }

    fun removeFromAlbum(albumId: String, item: GalleryItem) = guarded {
        val assetId = item.remoteId
        if (assetId == null) {
            _message.value = "That photo is not on the server, so it is not in the album."
            return@guarded
        }
        albums.removeItem(albumId, assetId)
            .onFailure { _message.value = "Could not take it out: ${it.message}" }
    }

    fun thumbnailModel(item: GalleryItem): Any? =
        item.localUri ?: item.remoteId?.let(library::thumbnailUrl)

    /** What to draw on an album card: the local file first, the server second. */
    fun coverModel(album: AlbumSummary): Any? =
        album.coverLocalUri ?: album.coverRemoteId?.let(library::thumbnailUrl)

    private fun guarded(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            block()
            _busy.value = false
        }
    }
}
