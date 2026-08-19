package com.kadr.app.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadr.app.R
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.ui.rememberHaptics

/**
 * One album's photos, paged like the timeline and in the same order (§15).
 *
 * Long-pressing a photo offers to take it out. Taking it out of an album never
 * deletes anything — §2's rule holds here as everywhere, and the wording says so
 * rather than leaving anyone to guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    albumName: String,
    onBack: () -> Unit,
    onOpenPhoto: (GalleryItem) -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val photos = remember(albumId) { viewModel.pages(albumId) }.collectAsLazyPagingItems()
    val count by viewModel.observeCount(albumId)
        .collectAsStateWithLifecycle(initialValue = 0)
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberHaptics()

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // One sheet for both photo actions: two separate long-press gestures would
    // be a guessing game.
    var acting by remember { mutableStateOf<GalleryItem?>(null) }
    var removing by remember { mutableStateOf<GalleryItem?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(albumName)
                        Text(
                            text = pluralStringResource(R.plurals.albums_photo_count, count, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.album_rename))
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.album_delete))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(
                count = photos.itemCount,
                key = photos.itemKey { it.key },
            ) { index ->
                val item = photos[index] ?: return@items
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(
                            onClick = { onOpenPhoto(item) },
                            onLongClick = {
                                haptics.select()
                                acting = item
                            },
                        ),
                ) {
                    AsyncImage(
                        model = viewModel.thumbnailModel(item),
                        contentDescription = item.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.album_rename),
            initial = albumName,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                viewModel.rename(albumId, name)
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.album_delete_title)) },
            text = {
                Text(stringResource(R.string.album_delete_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        viewModel.delete(albumId)
                        onBack()
                    },
                ) { Text(stringResource(R.string.album_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    acting?.let { item ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(item.filename) },
            text = { Text(stringResource(R.string.album_photo_options)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        acting = null
                        viewModel.setCover(albumId, item)
                    },
                ) { Text(stringResource(R.string.album_make_cover)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        removing = item
                        acting = null
                    },
                ) { Text(stringResource(R.string.album_take_out)) }
            },
        )
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.album_take_out_title)) },
            text = { Text(stringResource(R.string.album_take_out_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        viewModel.removeFromAlbum(albumId, item)
                    },
                ) { Text(stringResource(R.string.album_take_out_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Kept so the empty state has somewhere obvious to point. */
@Composable
internal fun AlbumEmptyHint(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(
            text = stringResource(R.string.album_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
