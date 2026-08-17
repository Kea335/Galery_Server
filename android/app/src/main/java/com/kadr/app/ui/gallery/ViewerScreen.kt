package com.kadr.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.ui.formatBytes
import com.kadr.app.ui.formatDuration
import com.kadr.app.ui.theme.KadrMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val CAPTION_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Locale.getDefault())

/**
 * Full-screen viewer (§12, screen 3): a horizontal pager of zoomable images.
 * Dragging down dismisses it, and the background dims in proportion to how far
 * the drag has gone.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ViewerScreen(
    viewModel: GalleryViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    startKey: String,
    startCapturedAt: Long,
    onClose: () -> Unit,
) {
    val photos = viewModel.photos.collectAsLazyPagingItems()

    // Which page the tapped photo is on is a question for the database: the
    // pages around it may never have been read. Until the answer arrives there
    // is nothing to page through, so the pager is not built at all — building it
    // at 0 first would flash the newest photo before jumping.
    var startIndex by remember(startKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(startKey) {
        startIndex = viewModel.positionOf(startKey, startCapturedAt)
    }

    val start = startIndex
    if (start == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (start < 0) {
        // Deleted between the tap and the query.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = start,
        // Placeholders are on for this pager, so the count is the whole library
        // from the first frame and page `start` exists before it is loaded.
        pageCount = { photos.itemCount },
    )

    var chromeVisible by remember { mutableStateOf(true) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // popBackStack() is not idempotent here: called once per pointer event it
    // pops the timeline off too and leaves a blank screen. Close exactly once.
    var closing by remember { mutableStateOf(false) }
    val closeOnce = {
        if (!closing) {
            closing = true
            onClose()
        }
    }

    // Background opacity tracks the drag, so the timeline shows through as the
    // photo is pulled away.
    val dimAlpha by animateFloatAsState(
        targetValue = (1f - (abs(dragOffset) / DISMISS_DISTANCE_PX).coerceIn(0f, 1f)),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "viewerDim",
    )

    val current = photos.peek(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = dimAlpha)),
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            // Null while that page is still being read — the pager knows the
            // photo is there, just not yet what it is.
            val item = photos[page] ?: return@HorizontalPager
            val mediaUri = viewModel.mediaUri(item)

            if (item.isVideo && mediaUri != null) {
                // §11 hands vertical drags to brightness and volume, so a video
                // page has no drag-to-dismiss; the back control closes it.
                VideoPlayer(
                    item = item,
                    mediaUri = mediaUri,
                    posterModel = viewModel.thumbnailModel(item),
                    playerFactory = viewModel.playerFactory,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    // Only the page in view gets a decoder; the pager keeps a
                    // neighbour composed and two hardware decoders would fight.
                    active = page == pagerState.currentPage,
                )
                return@HorizontalPager
            }

            ZoomableMedia(
                item = item,
                model = mediaUri,
                sharedKey = "photo-${item.key}",
                animatedVisibilityScope = animatedVisibilityScope,
                onTap = { chromeVisible = !chromeVisible },
                // While the finger is down we only report how far it has come,
                // so the background can dim in proportion (§12).
                onDragProgress = { offset -> dragOffset = offset },
                // The decision belongs to the release, not to every frame.
                onDragRelease = { offset ->
                    if (abs(offset) > DISMISS_THRESHOLD_PX) closeOnce() else dragOffset = 0f
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(spring(dampingRatio = 0.8f)),
            exit = fadeOut(spring(dampingRatio = 0.8f)),
        ) {
            ViewerChrome(item = current, onClose = closeOnce)
        }
    }
}

@Composable
private fun ViewerChrome(item: GalleryItem?, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }

            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = item?.filename.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.let {
                    Text(
                        text = buildString {
                            append(
                                CAPTION_FORMAT.format(
                                    Instant.ofEpochMilli(it.capturedAt).atZone(ZoneId.systemDefault()),
                                ),
                            )
                            if (it.width != null && it.height != null) {
                                append(" · ${it.width}×${it.height}")
                            }
                            it.durationMs?.let { ms -> append(" · ${formatDuration(ms)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = KadrMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (item?.isLocalOnly == true) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Not backed up yet",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(end = 16.dp).size(18.dp),
                )
            }
        }
    }
}

/**
 * One page: pinch and double-tap to zoom, drag to pan while zoomed, drag down
 * to dismiss while at rest.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.ZoomableMedia(
    item: GalleryItem,
    model: Any?,
    sharedKey: String,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onTap: () -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragRelease: (Float) -> Unit,
) {
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.key) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.key) { mutableFloatStateOf(0f) }
    var dragY by remember(item.key) { mutableFloatStateOf(0f) }

    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "zoom",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.key) {
                // One detector for everything on purpose.
                //
                // Stacking detectTransformGestures / detectTapGestures /
                // detectVerticalDragGestures does not work here: each consumes
                // the initial down, and whichever one Compose happens to reach
                // first starves the others — the symptom being a pager that
                // will not page and a dismiss drag that registers as a tap.
                // Deciding the gesture's intent in one place removes the
                // ordering question entirely.
                val slop = viewConfiguration.touchSlop
                var lastTapAt = 0L

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    var totalX = 0f
                    var totalY = 0f
                    var intent = GestureIntent.Undecided

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2) {
                            intent = GestureIntent.Zoom
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val pan = event.calculatePan()
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val pan = event.calculatePan()
                        totalX += pan.x
                        totalY += pan.y

                        if (intent == GestureIntent.Undecided) {
                            intent = when {
                                scale > 1f && (abs(totalX) > slop || abs(totalY) > slop) ->
                                    GestureIntent.Pan
                                // Clearly downward, not a sloppy horizontal flick.
                                abs(totalY) > slop && abs(totalY) > abs(totalX) * 1.5f ->
                                    GestureIntent.Dismiss
                                // Sideways belongs to the pager; do not consume.
                                abs(totalX) > slop -> GestureIntent.Page
                                else -> GestureIntent.Undecided
                            }
                        }

                        when (intent) {
                            GestureIntent.Pan -> {
                                offsetX += pan.x
                                offsetY += pan.y
                                event.changes.forEach { it.consume() }
                            }

                            GestureIntent.Dismiss -> {
                                dragY += pan.y
                                onDragProgress(dragY)
                                event.changes.forEach { it.consume() }
                            }

                            else -> Unit
                        }
                    }

                    when (intent) {
                        GestureIntent.Dismiss -> {
                            onDragRelease(dragY)
                            dragY = 0f
                        }

                        GestureIntent.Undecided -> {
                            // Never moved: a tap.
                            val now = System.currentTimeMillis()
                            if (now - startedAt < TAP_TIMEOUT_MS) {
                                if (now - lastTapAt < DOUBLE_TAP_WINDOW_MS) {
                                    scale = if (scale > 1f) 1f else 2.5f
                                    offsetX = 0f
                                    offsetY = 0f
                                    // The first of the two taps already toggled
                                    // the chrome; put it back.
                                    onTap()
                                    lastTapAt = 0L
                                } else {
                                    lastTapAt = now
                                    onTap()
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = sharedKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offsetX
                    translationY = offsetY + dragY
                },
        )

        if (item.isVideo) {
            // Playback itself is M5; until then the poster frame and a clear
            // affordance beat a broken player.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShapeCompat)
                        .padding(8.dp),
                )
                Text(
                    text = "Playback arrives in M5 · ${item.durationMs?.let(::formatDuration) ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

private val CircleShapeCompat = androidx.compose.foundation.shape.CircleShape

private enum class GestureIntent { Undecided, Zoom, Pan, Dismiss, Page }

private const val MAX_ZOOM = 5f

/** How far the photo travels before the background is fully dimmed away. */
private const val DISMISS_DISTANCE_PX = 700f

/** Past this on release, the viewer closes rather than snapping back. */
private const val DISMISS_THRESHOLD_PX = 220f
private const val TAP_TIMEOUT_MS = 300L
private const val DOUBLE_TAP_WINDOW_MS = 280L
