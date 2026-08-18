package com.kadr.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.repo.ServerFull
import com.kadr.app.ui.albums.AlbumsViewModel
import com.kadr.app.ui.formatBytes
import com.kadr.app.ui.formatDuration
import com.kadr.app.ui.rememberHaptics
import com.kadr.app.ui.theme.KadrAmber
import com.kadr.app.ui.theme.KadrBase
import com.kadr.app.ui.theme.KadrMuted

/**
 * The home screen (§12): every photo this phone has and every photo the server
 * has, newest first, in one list. Month dividers read like a contents page; a
 * pinch moves between 2, 3 and 5 columns.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.TimelineScreen(
    viewModel: GalleryViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenPhoto: (item: GalleryItem) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAlbums: () -> Unit,
) {
    val albumsViewModel: AlbumsViewModel = hiltViewModel()
    val entries = viewModel.entries.collectAsLazyPagingItems()
    val photoCount by viewModel.photoCount.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val freeUpPlan by viewModel.freeUpPlan.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val backingUp by viewModel.backingUp.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val serverFull by viewModel.serverFull.collectAsStateWithLifecycle()

    val selecting = selection.isNotEmpty()
    var choosingAlbum by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberHaptics()

    // Back gets you out of the selection before it gets you out of the screen.
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    // Android shows its own confirmation for the deletion; this only reports
    // back so the rows can be marked freed.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { freeUpPlan?.let(viewModel::onFreeUpFinished) }

    // §12: a small buzz when the queue drains, so a finished backup registers
    // without demanding attention.
    var wasBackingUp by remember { mutableStateOf(false) }
    LaunchedEffect(backingUp) {
        if (wasBackingUp && !backingUp) haptics.confirm()
        wasBackingUp = backingUp
    }

    // The chrome floats over the grid, so the grid has to know how tall it
    // actually is — a guessed constant leaves the first month header clipped
    // behind it, and the height changes when the upload hairline appears.
    val density = LocalDensity.current
    var chromeHeight by remember { mutableIntStateOf(0) }

    // §11's "single detail that makes the gallery feel alive": long-press a
    // video cell and it plays in place, muted, until the finger lifts. One
    // player for the whole grid — building one per cell would be absurd.
    var previewKey by remember { mutableStateOf<String?>(null) }
    var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.release()
            previewPlayer = null
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // The month of whatever is at the top right now, for the floating pill.
    // `peek` rather than `get`: reading the top row must not be what triggers
    // the next page to load.
    val visibleMonth by remember {
        derivedStateOf {
            val firstIndex = gridState.firstVisibleItemIndex
            (firstIndex downTo 0)
                .asSequence()
                .mapNotNull { entries.peek(it) }
                .filterIsInstance<TimelineEntry.MonthHeader>()
                .firstOrNull()
                ?.label
        }
    }
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = KadrBase,
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(viewModel.columns),
                contentPadding = PaddingValues(
                    top = with(density) { chromeHeight.toDp() },
                    bottom = 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomColumns(
                        onZoomIn = { viewModel.stepColumns(zoomingIn = true) },
                        onZoomOut = { viewModel.stepColumns(zoomingIn = false) },
                    ),
            ) {
                items(
                    count = entries.itemCount,
                    span = { index ->
                        if (entries.peek(index) is TimelineEntry.MonthHeader) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                    key = entries.itemKey { entry ->
                        when (entry) {
                            is TimelineEntry.MonthHeader -> "month-${entry.month}"
                            is TimelineEntry.Photo -> entry.item.key
                        }
                    },
                ) { index ->
                    when (val entry = entries[index]) {
                        null -> Unit
                        is TimelineEntry.MonthHeader -> MonthDivider(
                            label = entry.label,
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ),
                        )

                        is TimelineEntry.Photo -> {
                            GalleryCell(
                                item = entry.item,
                                model = viewModel.thumbnailModel(entry.item),
                                animatedVisibilityScope = animatedVisibilityScope,
                                previewPlayer = previewPlayer.takeIf {
                                    previewKey == entry.item.key
                                },
                                selecting = selecting,
                                selected = entry.item.key in selection,
                                onClick = {
                                    if (selecting) {
                                        haptics.select()
                                        viewModel.toggleSelection(entry.item)
                                    } else {
                                        onOpenPhoto(entry.item)
                                    }
                                },
                                onLongPress = {
                                    haptics.select()
                                    viewModel.toggleSelection(entry.item)
                                },
                                onPreviewStart = {
                                    val uri = viewModel.mediaUri(entry.item) ?: return@GalleryCell
                                    haptics.select()
                                    val player = previewPlayer
                                        ?: viewModel.playerFactory.createPreviewPlayer()
                                            .also { previewPlayer = it }
                                    player.setMediaItem(MediaItem.fromUri(uri))
                                    player.prepare()
                                    player.playWhenReady = true
                                    previewKey = entry.item.key
                                },
                                onPreviewStop = {
                                    previewPlayer?.playWhenReady = false
                                    previewKey = null
                                },
                                modifier = Modifier.animateItem(
                                    placementSpec = spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            if (selecting) {
                SelectionChrome(
                    count = selection.size,
                    busy = busy,
                    onClose = viewModel::clearSelection,
                    onAddToAlbum = { choosingAlbum = true },
                    onFreeUp = viewModel::prepareFreeUp,
                    modifier = Modifier.onSizeChanged { chromeHeight = it.height },
                )
            } else {
                TopChrome(
                    photoCount = photoCount,
                    syncing = syncing,
                    backingUp = backingUp,
                    backupFraction = progress?.overallFraction,
                    serverFull = serverFull,
                    onOpenBackup = onOpenBackup,
                    onOpenAlbums = onOpenAlbums,
                    modifier = Modifier.onSizeChanged { chromeHeight = it.height },
                )
            }

            // Sticky month, but only while the finger is moving (§12).
            AnimatedVisibility(
                visible = scrolling && visibleMonth != null,
                enter = fadeIn(spring(dampingRatio = 0.8f)),
                exit = fadeOut(spring(dampingRatio = 0.8f)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                    ),
            ) {
                Text(
                    text = visibleMonth.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            // Only once the first page has actually come back — otherwise "no
            // photos yet" flashes up on every cold start before the answer is in.
            val loaded = entries.loadState.refresh is LoadState.NotLoading
            if (loaded && entries.itemCount == 0) {
                EmptyTimeline(
                    syncing = syncing,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // §10.7: the app says exactly what is about to go, and from where, before
    // Android's own dialog asks again.
    freeUpPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::cancelFreeUp,
            title = { Text("Remove ${plan.assets.size} from this phone?") },
            text = {
                Text(
                    buildString {
                        append("${formatBytes(plan.totalBytes)} will be freed. ")
                        append("The server keeps its copy — these stay in the timeline.")
                        if (plan.withheld > 0) {
                            append(
                                "\n\n${plan.withheld} were left alone: the server could not " +
                                    "confirm it still has them.",
                            )
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val request = viewModel.deleteRequestFor(plan)
                        if (request != null) {
                            deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
                        } else {
                            viewModel.deleteWithoutSystemDialog(plan)
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFreeUp) { Text("Cancel") }
            },
        )
    }

    if (choosingAlbum) {
        AlbumPicker(
            selectionSize = selection.size,
            onDismiss = { choosingAlbum = false },
            onChosen = { albumId ->
                choosingAlbum = false
                albumsViewModel.addToAlbum(albumId, selection) { viewModel.clearSelection() }
            },
        )
    }
}

/**
 * Which album the picked photos should go into.
 *
 * Empty-handed on purpose when there are no albums yet: sending someone to the
 * albums screen to make one is clearer than a dialog that offers nothing.
 */
@Composable
private fun AlbumPicker(
    selectionSize: Int,
    onDismiss: () -> Unit,
    onChosen: (String) -> Unit,
    albumsViewModel: AlbumsViewModel = hiltViewModel(),
) {
    val albums by albumsViewModel.list.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (selectionSize == 1) "Add 1 photo to…" else "Add $selectionSize photos to…")
        },
        text = {
            if (albums.isEmpty()) {
                Text("No albums yet. Make one from the albums screen first.")
            } else {
                Column {
                    albums.forEach { album ->
                        Text(
                            text = "${album.name}  ·  ${album.itemCount}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChosen(album.id) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the top of the screen becomes while photos are picked (§12).
 *
 * It replaces the usual chrome rather than stacking on top of it: the sync and
 * backup state is not what anyone is looking at mid-selection, and the grid
 * measures its own top inset from whatever is here.
 */
@Composable
private fun SelectionChrome(
    count: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onAddToAlbum: () -> Unit,
    onFreeUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Stop selecting",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )

        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = KadrAmber,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 4.dp),
            )
        } else {
            IconButton(onClick = onAddToAlbum) {
                Icon(
                    imageVector = Icons.Default.LibraryAdd,
                    contentDescription = "Add to an album",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onFreeUp) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Free up space on this phone",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TopChrome(
    photoCount: Int,
    syncing: Boolean,
    backingUp: Boolean,
    backupFraction: Float?,
    serverFull: ServerFull?,
    onOpenBackup: () -> Unit,
    onOpenAlbums: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KadrBase.copy(alpha = 0.86f))
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kadr", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = "$photoCount photos",
                    style = MaterialTheme.typography.bodySmall,
                    color = KadrMuted,
                )
            }

            if (syncing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = KadrAmber,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(onClick = onOpenAlbums) {
                Icon(
                    imageVector = Icons.Default.PhotoAlbum,
                    contentDescription = "Albums",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onOpenBackup) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Backup status",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // §16 made the library "whatever fits on the disk", so running out is a
        // normal ending. One line that says it, not a hundred failed files.
        serverFull?.let { full ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onOpenBackup)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = "The server is out of space",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = full.freeBytes?.let { "${formatBytes(it)} left — free some up or add a disk" }
                            ?: "Free some up or add a disk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 2 dp hairline while uploading — no modal, no blocking dialog (§12).
        if (backingUp) {
            if (backupFraction != null && backupFraction > 0f) {
                LinearProgressIndicator(
                    progress = { backupFraction },
                    color = KadrAmber,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else {
                LinearProgressIndicator(
                    color = KadrAmber,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthDivider(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 12.dp),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.GalleryCell(
    item: GalleryItem,
    model: Any?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    previewPlayer: Player?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPreviewStart: () -> Unit,
    onPreviewStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The picked photo shrinks back a little, so a selection reads at a glance
    // from across the grid rather than only by its tick. See the clamp below.
    val inset by animateDpAsState(
        targetValue = if (selected) 8.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "cellInset",
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(item.key, item.isVideo, selecting) {
                awaitEachGesture {
                    awaitFirstDown()
                    var scrolledAway = false

                    // A tap opens the viewer; holding a video cell previews it.
                    // Scrolling must do neither, and a null from
                    // waitForUpOrCancellation is ambiguous between "held" and
                    // "the grid took the gesture" — so track it explicitly.
                    val up = withTimeoutOrNull(LONG_PRESS_MS) {
                        waitForUpOrCancellation().also { if (it == null) scrolledAway = true }
                    }

                    when {
                        scrolledAway -> Unit
                        up != null -> onClick()

                        // While picking, a hold is just another way to pick —
                        // previewing a video mid-selection would be a fight
                        // between two meanings of the same gesture.
                        selecting -> {
                            onLongPress()
                            waitForUpOrCancellation()
                        }

                        item.isVideo -> {
                            onPreviewStart()
                            waitForUpOrCancellation()
                            onPreviewStop()
                        }

                        // §12: holding a photo is what starts a selection.
                        else -> {
                            onLongPress()
                            waitForUpOrCancellation()
                        }
                    }
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // Clamped because a spring overshoots by definition: on the way
                // back to zero it dips below, and padding cannot be negative —
                // it throws rather than clipping, taking the app with it.
                .padding(inset.coerceAtLeast(0.dp))
                .clip(RoundedCornerShape(if (selected) 8.dp else 2.dp))
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "photo-${item.key}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
        )

        // Muted autoplay in place, over the still (§11).
        if (previewPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { view -> view.player = previewPlayer },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (item.isVideo) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                item.durationMs?.let {
                    Text(
                        text = formatDuration(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        // Only what is NOT yet safe gets a mark. Backed-up is the quiet default.
        if (item.isLocalOnly && !selecting) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = "Not backed up yet",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(12.dp),
            )
        }

        if (selecting) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) KadrAmber else Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyTimeline(syncing: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (syncing) "Reading the library…" else "Nothing here yet",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Grant access and run a backup — photos show up as soon as they are indexed.",
            style = MaterialTheme.typography.bodyMedium,
            color = KadrMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Pinch anywhere on the grid to change the column count. Single-finger events
 * are left untouched so vertical scrolling still belongs to the list.
 */
private fun Modifier.pinchToZoomColumns(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulated = 1f
        var handled = false

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } < 2) {
                if (event.changes.none { it.pressed }) break
                continue
            }

            accumulated *= event.calculateZoom()
            event.changes.forEach { it.consume() }

            if (!handled) {
                when {
                    accumulated > ZOOM_IN_THRESHOLD -> {
                        onZoomIn(); handled = true
                    }

                    accumulated < ZOOM_OUT_THRESHOLD -> {
                        onZoomOut(); handled = true
                    }
                }
            }
        }
    }
}

private const val ZOOM_IN_THRESHOLD = 1.3f
private const val ZOOM_OUT_THRESHOLD = 0.77f

/** Held this long on a video cell and the preview starts. */
private const val LONG_PRESS_MS = 350L

