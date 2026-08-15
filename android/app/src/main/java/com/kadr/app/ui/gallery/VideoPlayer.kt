package com.kadr.app.ui.gallery

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.video.PlayerFactory
import com.kadr.app.ui.formatDuration
import com.kadr.app.ui.theme.KadrAmber
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)

/**
 * Full-screen video (§11).
 *
 * The player is torn down when the screen stops and rebuilt with the saved
 * position when it comes back, so leaving the app mid-clip and returning puts
 * you where you were. A poster frame sits under the surface until the decoder
 * produces its first frame, which is what stops the black flash on open.
 */
@Composable
fun VideoPlayer(
    item: GalleryItem,
    mediaUri: String,
    posterModel: Any?,
    playerFactory: PlayerFactory,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var savedPosition by rememberSaveable(item.key) { mutableLongStateOf(0L) }
    var playWhenReady by rememberSaveable(item.key) { mutableStateOf(true) }

    var player by remember(item.key) { mutableStateOf<Player?>(null) }
    var firstFrameRendered by remember(item.key) { mutableStateOf(false) }
    var isPlaying by remember(item.key) { mutableStateOf(false) }
    var position by remember(item.key) { mutableLongStateOf(0L) }
    var duration by remember(item.key) { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableStateOf(1) }
    var scrubbing by remember(item.key) { mutableStateOf(false) }

    // Transient overlays for the gesture feedback §11 asks for.
    var seekHint by remember { mutableStateOf<String?>(null) }
    var levelHint by remember { mutableStateOf<Pair<String, Float>?>(null) }

    DisposableEffect(lifecycleOwner, item.key, active, mediaUri) {
        fun open() {
            if (!active || player != null) return
            player = playerFactory.create().apply {
                setMediaItem(MediaItem.fromUri(mediaUri))
                seekTo(savedPosition)
                this.playWhenReady = playWhenReady
                prepare()
            }
        }

        fun close() {
            player?.let {
                savedPosition = it.currentPosition
                playWhenReady = it.playWhenReady
                it.release()
            }
            player = null
            firstFrameRendered = false
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> open()
                // §11: released in onStop, position restored on the way back.
                Lifecycle.Event.ON_STOP -> close()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        open()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            close()
        }
    }

    val activePlayer = player
    DisposableEffect(activePlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        activePlayer?.addListener(listener)
        onDispose { activePlayer?.removeListener(listener) }
    }

    // Poll rather than listen: position has no callback, and 200 ms is smooth
    // enough for a progress bar while costing nothing.
    LaunchedEffect(activePlayer, scrubbing) {
        while (activePlayer != null && !scrubbing) {
            position = activePlayer.currentPosition
            duration = activePlayer.duration.coerceAtLeast(0L)
            delay(200)
        }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }
    LaunchedEffect(levelHint) {
        if (levelHint != null) {
            delay(700)
            levelHint = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .videoGestures(
                onTap = onToggleChrome,
                onDoubleTapSeek = { forward ->
                    activePlayer?.let {
                        val target = (it.currentPosition + if (forward) 10_000 else -10_000)
                            .coerceIn(0L, it.duration.coerceAtLeast(0L))
                        it.seekTo(target)
                        seekHint = if (forward) "+10s" else "−10s"
                    }
                },
                onScrub = { fraction ->
                    activePlayer?.let {
                        val total = it.duration.coerceAtLeast(0L)
                        if (total > 0) {
                            val target = (it.currentPosition + (fraction * total)).toLong()
                                .coerceIn(0L, total)
                            it.seekTo(target)
                            seekHint = formatDuration(target)
                        }
                    }
                },
                onBrightness = { delta ->
                    val window = (context as? Activity)?.window ?: return@videoGestures
                    val attrs = window.attributes
                    val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                    val next = (current + delta).coerceIn(0.01f, 1f)
                    attrs.screenBrightness = next
                    window.attributes = attrs
                    levelHint = "Brightness" to next
                },
                onVolume = { delta ->
                    activePlayer?.let {
                        val next = (it.volume + delta).coerceIn(0f, 1f)
                        it.volume = next
                        muted = next == 0f
                        levelHint = "Volume" to next
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Instant first frame (§11): the thumbnail holds the screen until the
        // decoder catches up.
        AnimatedVisibility(
            visible = !firstFrameRendered,
            enter = fadeIn(spring(dampingRatio = 0.8f)),
            exit = fadeOut(spring(dampingRatio = 0.8f)),
        ) {
            AsyncImage(
                model = posterModel,
                contentDescription = item.filename,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view -> view.player = activePlayer },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        seekHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        levelHint?.let { (label, value) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                Text(
                    "${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = KadrAmber,
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(spring(dampingRatio = 0.8f)),
            exit = fadeOut(spring(dampingRatio = 0.8f)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlaybackControls(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                muted = muted,
                speed = SPEEDS[speedIndex],
                onPlayPause = {
                    activePlayer?.let { it.playWhenReady = !it.playWhenReady }
                },
                onSeek = { value ->
                    scrubbing = true
                    position = value.toLong()
                },
                onSeekFinished = {
                    activePlayer?.seekTo(position)
                    scrubbing = false
                },
                onStepFrame = {
                    // Frame-step when paused (§11). Media3 has no frame API, so
                    // this nudges by roughly one frame at 30 fps.
                    activePlayer?.let {
                        it.playWhenReady = false
                        it.seekTo((it.currentPosition + 33).coerceAtMost(it.duration))
                    }
                },
                onToggleMute = {
                    activePlayer?.let {
                        muted = !muted
                        it.volume = if (muted) 0f else 1f
                    }
                },
                onCycleSpeed = {
                    speedIndex = (speedIndex + 1) % SPEEDS.size
                    activePlayer?.playbackParameters = PlaybackParameters(SPEEDS[speedIndex])
                },
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    muted: Boolean,
    speed: Float,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onStepFrame: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Slider(
            value = position.toFloat(),
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
            colors = SliderDefaults.colors(
                thumbColor = KadrAmber,
                activeTrackColor = KadrAmber,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }

            Text(
                text = "${formatDuration(position)} / ${formatDuration(duration)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )

            if (!isPlaying) {
                IconButton(onClick = onStepFrame) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Step one frame",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            IconButton(onClick = onCycleSpeed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Playback speed",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "${speed}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }

            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * §11's gesture set, in one detector for the same reason the image viewer uses
 * one: stacked detectors fight over the initial down and the loser never fires.
 *
 * - tap: toggle the chrome
 * - double-tap left / right: ∓10 s
 * - horizontal drag: precise seek
 * - vertical drag on the left half: brightness, right half: volume
 */
private fun Modifier.videoGestures(
    onTap: () -> Unit,
    onDoubleTapSeek: (forward: Boolean) -> Unit,
    onScrub: (fraction: Float) -> Unit,
    onBrightness: (delta: Float) -> Unit,
    onVolume: (delta: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    var lastTapAt = 0L
    var lastTapX = 0f

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startedAt = System.currentTimeMillis()
        val startX = down.position.x
        val onLeftHalf = startX < size.width / 2f
        var totalX = 0f
        var totalY = 0f
        var mode = VideoGesture.Undecided

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break

            val pan = event.calculatePan()
            totalX += pan.x
            totalY += pan.y

            if (mode == VideoGesture.Undecided) {
                mode = when {
                    abs(totalX) > slop && abs(totalX) > abs(totalY) -> VideoGesture.Scrub
                    abs(totalY) > slop -> VideoGesture.Level
                    else -> VideoGesture.Undecided
                }
            }

            when (mode) {
                VideoGesture.Scrub -> {
                    onScrub(pan.x / size.width * SCRUB_SENSITIVITY)
                    event.changes.forEach { it.consume() }
                }

                VideoGesture.Level -> {
                    val delta = -pan.y / size.height
                    if (onLeftHalf) onBrightness(delta) else onVolume(delta)
                    event.changes.forEach { it.consume() }
                }

                VideoGesture.Undecided -> Unit
            }
        }

        if (mode == VideoGesture.Undecided) {
            val now = System.currentTimeMillis()
            if (now - startedAt < 300) {
                if (now - lastTapAt < 280 && abs(startX - lastTapX) < size.width / 4f) {
                    onDoubleTapSeek(!onLeftHalf)
                    // Undo the chrome toggle the first tap performed.
                    onTap()
                    lastTapAt = 0L
                } else {
                    lastTapAt = now
                    lastTapX = startX
                    onTap()
                }
            }
        }
    }
}

private enum class VideoGesture { Undecided, Scrub, Level }

/** A full screen-width drag covers a quarter of the clip — precise, not twitchy. */
private const val SCRUB_SENSITIVITY = 0.25f
