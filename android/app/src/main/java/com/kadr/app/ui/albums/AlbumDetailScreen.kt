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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                            text = if (count == 1) "1 photo" else "$count photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename album")
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete album")
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
                            onClick = {},
                            onLongClick = {
                                haptics.select()
                                removing = item
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
            title = "Rename album",
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
            title = { Text("Delete this album?") },
            text = {
                Text(
                    "The album goes, on every phone signed in to this server. " +
                        "Not one photo is deleted — they stay in the timeline.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        viewModel.delete(albumId)
                        onBack()
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Take this out of the album?") },
            text = { Text("It stays in the timeline and on the server. Only the album changes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        viewModel.removeFromAlbum(albumId, item)
                    },
                ) { Text("Take out") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Kept so the empty state has somewhere obvious to point. */
@Composable
internal fun AlbumEmptyHint(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(
            text = "Nothing in here yet. Pick photos in the timeline and add them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
