package com.example.gallery.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.example.gallery.utils.isVideoUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun FullScreenImage(
    images: List<Uri>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (Uri) -> Unit,
    // Feature 3: when true, Share and Delete buttons are hidden (read-only preview)
    isPreviewMode: Boolean = false,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }

    // Feature: stabilize pager when list mutates (e.g. background indexing or deletions)
    var activeUri by remember { mutableStateOf<Uri?>(images.getOrNull(initialIndex)) }

    LaunchedEffect(pagerState.currentPage) {
        images.getOrNull(pagerState.currentPage)?.let {
            activeUri = it
        }
    }

    LaunchedEffect(images) {
        val uri = activeUri ?: return@LaunchedEffect
        val newIndex = images.indexOf(uri)
        if (newIndex != -1) {
            if (newIndex != pagerState.currentPage) {
                pagerState.scrollToPage(newIndex)
            }
        } else {
            if (images.isEmpty()) {
                onClose()
            } else {
                val fallbackIndex = pagerState.currentPage.coerceAtMost(images.size - 1)
                pagerState.scrollToPage(fallbackIndex)
                activeUri = images[fallbackIndex]
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val window = remember(view) { (view.context as? Activity)?.window }

    // Track state for controls and gestures
    var isCurrentPageZoomed by remember { mutableStateOf(value = false) }
    var isDraggingDownGlobal by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    // Handle System Bars Visibility (Immersive Mode)
    LaunchedEffect(showControls) {
        window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            if (showControls) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // Restore system bars on exit
    DisposableEffect(Unit) {
        onDispose {
            window?.let {
                val controller = WindowCompat.getInsetsController(it, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !isCurrentPageZoomed && !isDraggingDownGlobal
        ) { pageIndex ->
            val uri = images[pageIndex]

            val scale = remember { Animatable(1f) }
            val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
            val dismissOffset = remember { Animatable(0f) }
            var mediaAspectRatio by remember { mutableFloatStateOf(1f) }

            // Video Controller States Hoisted per Page Item
            var isPlaying by remember { mutableStateOf(false) }
            var currentPosition by remember { mutableStateOf(0) }
            var duration by remember { mutableStateOf(0) }
            var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }

            // Periodically update currentPosition when video plays
            LaunchedEffect(isPlaying) {
                if (isPlaying) {
                    while (true) {
                        videoViewInstance?.let {
                            currentPosition = it.currentPosition
                            duration = it.duration
                        }
                        delay(200)
                    }
                }
            }

            LaunchedEffect(scale.value) {
                if (pagerState.currentPage == pageIndex) {
                    isCurrentPageZoomed = scale.value > 1.05f
                }
            }

            val isCurrentPage = pagerState.currentPage == pageIndex

            LaunchedEffect(isCurrentPage) {
                if (isCurrentPage) {
                    if (uri.isVideoUri()) {
                        videoViewInstance?.start()
                        isPlaying = true
                    }
                } else {
                    scale.snapTo(1f)
                    offset.snapTo(Offset.Zero)
                    dismissOffset.snapTo(0f)
                    // Automatically pause running video if swiped away and reset to the beginning
                    videoViewInstance?.pause()
                    videoViewInstance?.seekTo(0)
                    currentPosition = 0
                    isPlaying = false
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val boxWidth = with(LocalDensity.current) { maxWidth.toPx() }
                val boxHeight = with(LocalDensity.current) { maxHeight.toPx() }

                // LAYER 1: Media Player/Viewer (Zoomable & Pannable Frame)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale.value,
                            scaleY = scale.value,
                            translationX = offset.value.x,
                            translationY = offset.value.y + dismissOffset.value,
                            alpha = (1f - (dismissOffset.value / boxHeight)).coerceIn(0f, 1f)
                        )
                ) {
                    if (uri.isVideoUri()) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(uri)
                                    setOnPreparedListener { mp ->
                                        duration = mp.duration
                                        mp.isLooping = true
                                        if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                            mediaAspectRatio = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
                                        }
                                        if (pagerState.currentPage == pageIndex) {
                                            start()
                                            isPlaying = true
                                        }
                                    }
                                    videoViewInstance = this
                                }
                            },
                            update = { videoView ->
                                if (videoView.tag != uri) {
                                    videoView.tag = uri
                                    videoView.setVideoURI(uri)
                                    if (pagerState.currentPage == pageIndex) {
                                        videoView.start()
                                        isPlaying = true
                                    } else {
                                        videoView.pause()
                                        isPlaying = false
                                    }
                                    videoViewInstance = videoView
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = uri,
                            onSuccess = { state ->
                                val intrinsicSize = state.painter.intrinsicSize
                                if (intrinsicSize.width > 0 && intrinsicSize.height > 0) {
                                    mediaAspectRatio = intrinsicSize.width / intrinsicSize.height
                                }
                            },
                            contentDescription = "Full screen image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // LAYER 2: Transparent Gesture Interceptor Overlay
                // Guarantees stable touch feedback over Native AndroidViews
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 1. Native Tap Detection (intercepts safely and accurately)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scope.launch {
                                        if (scale.value > 1.05f) {
                                            launch { scale.animateTo(1f) }
                                            launch { offset.animateTo(Offset.Zero) }
                                        } else {
                                            showControls = false
                                            launch { scale.animateTo(3f) }
                                        }
                                    }
                                },
                                onTap = {
                                    if (scale.value <= 1.05f) {
                                        showControls = !showControls
                                    }
                                }
                            )
                        }
                        // 2. Custom Transform & Drag Tracker
                        //
                        // A single hand-rolled gesture loop that unifies THREE gestures that would
                        // otherwise conflict if handled by separate detectors:
                        //   • pinch-to-zoom + pan-while-zoomed (updates `scale` / `offset`)
                        //   • swipe-down-to-dismiss when at 1x zoom (drives `dismissOffset`, and
                        //     `onClose()` once dragged past the threshold below)
                        //   • plain taps pass through untouched (we only consume when actually moving)
                        //
                        // awaitEachGesture runs once per finger-down; inside, we manually read each
                        // pointer event and decide zoom vs. dismiss based on the current scale.
                        .pointerInput(boxWidth, boxHeight) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var isDraggingDown = false
                                // Track state at start so pinching out aggressively doesn't accidentally trigger dismiss
                                val startedZoomedOut = scale.value <= 1.05f

                                // Consume events until all fingers lift, routing each to zoom or dismiss.
                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    if (scale.value > 1.05f || zoomChange != 1f) {
                                        // Only consume if actively moving (prevents blocking pure taps)
                                        val isMoving = zoomChange != 1f || abs(panChange.x) > 1f || abs(panChange.y) > 1f
                                        if (isMoving) {
                                            event.changes.forEach { it.consume() }
                                        }

                                        if (!scale.isRunning && !offset.isRunning) {
                                            if (showControls && isMoving) showControls = false

                                            scope.launch {
                                                val prospectiveScale = scale.value * zoomChange
                                                val newScale = if (prospectiveScale < 1.05f) 1f else prospectiveScale.coerceIn(1f, 3f)

                                                val screenAspectRatio = boxWidth / boxHeight
                                                var displayedWidth = boxWidth
                                                var displayedHeight = boxHeight

                                                if (mediaAspectRatio > screenAspectRatio) {
                                                    displayedHeight = boxWidth / mediaAspectRatio
                                                } else {
                                                    displayedWidth = boxHeight * mediaAspectRatio
                                                }

                                                val maxX = ((displayedWidth * newScale) - boxWidth).coerceAtLeast(0f) / 2f
                                                val maxY = ((displayedHeight * newScale) - boxHeight).coerceAtLeast(0f) / 2f

                                                scale.snapTo(newScale)

                                                if (newScale > 1f) {
                                                    val newOffsetX = (offset.value.x + panChange.x).coerceIn(-maxX, maxX)
                                                    val newOffsetY = (offset.value.y + panChange.y).coerceIn(-maxY, maxY)
                                                    offset.snapTo(Offset(newOffsetX, newOffsetY))
                                                } else {
                                                    offset.snapTo(Offset.Zero)
                                                }
                                            }
                                        }
                                    } else if (startedZoomedOut) {
                                        // Swipe down detection
                                        if (!isDraggingDown && panChange.y > 10f && abs(panChange.y) > abs(panChange.x) * 2) {
                                            isDraggingDown = true
                                            isDraggingDownGlobal = true
                                            showControls = false
                                        }

                                        if (isDraggingDown) {
                                            if (panChange.y != 0f) {
                                                event.changes.forEach { it.consume() }
                                            }
                                            scope.launch {
                                                dismissOffset.snapTo(dismissOffset.value + panChange.y)
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // End of gesture: decide dismiss vs. snap-back. Dragged far enough
                                // (>200px) → animate the rest of the way off-screen and close;
                                // otherwise spring back to the original position.
                                if (isDraggingDown) {
                                    isDraggingDownGlobal = false
                                    if (dismissOffset.value > 200f) {
                                        scope.launch {
                                            dismissOffset.animateTo(boxHeight)
                                            onClose()
                                        }
                                    } else {
                                        scope.launch {
                                            dismissOffset.animateTo(0f)
                                        }
                                    }
                                } else if (scale.value <= 1.05f && scale.value != 1f) {
                                    // Make absolutely sure math is perfect for next tap cycle
                                    scope.launch {
                                        scale.snapTo(1f)
                                        offset.snapTo(Offset.Zero)
                                    }
                                }
                            }
                        }
                )

                // LAYER 3: Native Video Playback Controls (Always un-scaled at bottom boundary)
                if (uri.isVideoUri()) {
                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Surface(
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(Unit) { detectTapGestures {} } // Protects interface from passing taps backwards
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                        )
                                    )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .windowInsetsPadding(WindowInsets.navigationBars)
                                ) {
                                    // Slider (Seek bar)
                                    val durationFloat = duration.toFloat().coerceAtLeast(1f)
                                    val sliderPosition = currentPosition.toFloat().coerceIn(0f, durationFloat)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTime(currentPosition),
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        Slider(
                                            value = sliderPosition,
                                            onValueChange = { newValue ->
                                                currentPosition = newValue.toInt()
                                                videoViewInstance?.seekTo(newValue.toInt())
                                            },
                                            valueRange = 0f..durationFloat,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = MaterialTheme.colorScheme.primary,
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor = Color.Gray
                                            )
                                        )

                                        Text(
                                            text = formatTime(duration),
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    // Controls Bar Buttons (Play/Pause, Rewind, Fast Forward)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            videoViewInstance?.let {
                                                val newPos = (it.currentPosition - 10000).coerceAtLeast(0)
                                                it.seekTo(newPos)
                                                currentPosition = newPos
                                            }
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Replay10,
                                                contentDescription = "Rewind 10s",
                                                tint = Color.White
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(24.dp))

                                        IconButton(
                                            onClick = {
                                                videoViewInstance?.let {
                                                    if (it.isPlaying) {
                                                        it.pause()
                                                        isPlaying = false
                                                    } else {
                                                        it.start()
                                                        isPlaying = true
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    shape = androidx.compose.foundation.shape.CircleShape
                                                )
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                tint = Color.White
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(24.dp))

                                        IconButton(onClick = {
                                            videoViewInstance?.let {
                                                val newPos = (it.currentPosition + 10000).coerceAtMost(duration)
                                                it.seekTo(newPos)
                                                currentPosition = newPos
                                            }
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Forward10,
                                                contentDescription = "Forward 10s",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Global Top Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures {} } // Protects interface from passing taps backwards
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Box(modifier = Modifier.weight(1f))

                if (!isPreviewMode) {
                    IconButton(onClick = {
                        val currentUri = images[pagerState.currentPage]
                        val isVideo = currentUri.isVideoUri()
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, currentUri)
                            type = if (isVideo) "video/*" else "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, if (isVideo) "Share Video" else "Share Image"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }

                    IconButton(onClick = {
                        val currentUri = images[pagerState.currentPage]
                        onDelete(currentUri)
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FullScreenImagePreview() {
    val mockImages = List(3) {
        "android.resource://com.example.gallery/drawable/ic_launcher_background".toUri()
    }
    MaterialTheme {
        Surface {
            FullScreenImage(
                images = mockImages,
                initialIndex = 0,
                onClose = {},
                onDelete = {}
            )
        }
    }
}

private fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}