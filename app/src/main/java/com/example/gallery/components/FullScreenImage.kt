package com.example.gallery.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
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
            var imageAspectRatio by remember { mutableFloatStateOf(1f) }

            LaunchedEffect(scale.value) {
                if (pagerState.currentPage == pageIndex) {
                    isCurrentPageZoomed = scale.value > 1.05f
                }
            }

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != pageIndex) {
                    scale.snapTo(1f)
                    offset.snapTo(Offset.Zero)
                    dismissOffset.snapTo(0f)
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val boxWidth = with(LocalDensity.current) { maxWidth.toPx() }
                val boxHeight = with(LocalDensity.current) { maxHeight.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(boxWidth, boxHeight) {
                            var lastTapTime = 0L
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var isDraggingDown = false
                                var hasMovedSignificant = false
                                var isTransforming = false

                                val now = System.currentTimeMillis()
                                val isDoubleTap = (now - lastTapTime) < 300

                                if (isDoubleTap) {
                                    lastTapTime = 0L
                                    scope.launch {
                                        if (scale.value > 1.05f) {
                                            launch { scale.animateTo(1f) }
                                            launch { offset.animateTo(Offset.Zero) }
                                        } else {
                                            launch { scale.animateTo(3f) }
                                        }
                                    }
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    if ((abs(panChange.x) > 2f) || (abs(panChange.y) > 2f) || (zoomChange != 1f)) {
                                        hasMovedSignificant = true
                                    }

                                    if (scale.value > 1.05f || zoomChange != 1f) {
                                        isTransforming = true
                                        event.changes.forEach { it.consume() }

                                        if (!scale.isRunning && !offset.isRunning) {
                                            scope.launch {
                                                val newScale =
                                                    (scale.value * zoomChange).coerceIn(1f, 3f)

                                                val screenAspectRatio = boxWidth / boxHeight
                                                var displayedWidth = boxWidth
                                                var displayedHeight = boxHeight

                                                if (imageAspectRatio > screenAspectRatio) {
                                                    displayedHeight = boxWidth / imageAspectRatio
                                                } else {
                                                    displayedWidth = boxHeight * imageAspectRatio
                                                }

                                                val maxX =
                                                    ((displayedWidth * newScale) - boxWidth).coerceAtLeast(
                                                        0f
                                                    ) / 2f
                                                val maxY =
                                                    ((displayedHeight * newScale) - boxHeight).coerceAtLeast(
                                                        0f
                                                    ) / 2f

                                                scale.snapTo(newScale)

                                                if (newScale > 1f) {
                                                    val newOffsetX =
                                                        (offset.value.x + panChange.x).coerceIn(
                                                            -maxX,
                                                            maxX
                                                        )
                                                    val newOffsetY =
                                                        (offset.value.y + panChange.y).coerceIn(
                                                            -maxY,
                                                            maxY
                                                        )
                                                    offset.snapTo(Offset(newOffsetX, newOffsetY))
                                                } else {
                                                    offset.snapTo(Offset.Zero)
                                                }
                                            }
                                        }
                                    } else if (!isDoubleTap) {
                                        // Swipe down detection
                                        if (!isDraggingDown && panChange.y > 10f && abs(panChange.y) > abs(
                                                panChange.x
                                            ) * 2
                                        ) {
                                            isDraggingDown = true
                                            isDraggingDownGlobal = true
                                            showControls = false
                                        }

                                        if (isDraggingDown) {
                                            event.changes.forEach { it.consume() }
                                            scope.launch {
                                                dismissOffset.snapTo(dismissOffset.value + panChange.y)
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // Toggle controls on single tap
                                if (!isDoubleTap && !hasMovedSignificant && !isTransforming && !isDraggingDown) {
                                    showControls = !showControls
                                    lastTapTime = now
                                } else if (!isDoubleTap) {
                                    lastTapTime = now
                                }

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
                                }
                            }
                        }
                ) {
                    AsyncImage(
                        model = uri,
                        onSuccess = { state ->
                            val intrinsicSize = state.painter.intrinsicSize
                            if (intrinsicSize.width > 0 && intrinsicSize.height > 0) {
                                imageAspectRatio = intrinsicSize.width / intrinsicSize.height
                            }
                        },
                        contentDescription = "Full screen image",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale.value,
                                scaleY = scale.value,
                                translationX = offset.value.x,
                                translationY = offset.value.y + dismissOffset.value,
                                alpha = (1f - (dismissOffset.value / boxHeight)).coerceIn(0f, 1f)
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // Top Controls with Dark Gradient
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Box(modifier = Modifier.weight(1f))

                // Feature 3: Share and Delete are hidden in preview mode
                if (!isPreviewMode) {
                    IconButton(onClick = {
                        val currentUri = images[pagerState.currentPage]
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, currentUri)
                            type = "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }

                    IconButton(onClick = {
                        val currentUri = images[pagerState.currentPage]
                        onDelete(currentUri)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
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
