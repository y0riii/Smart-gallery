package com.example.gallery.components

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import com.example.gallery.ui.theme.AppConfig
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGrid(
    images: List<Uri>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    // ── Feature 2: column count driven from outside ──────────────────────────
    columnCount: Int = 3,
    onColumnCountChange: (Int) -> Unit = {},
    // ── Feature 1: selection state ───────────────────────────────────────────
    selectedUris: Set<Uri> = emptySet(),
    isSelecting: Boolean = false,
    onUpdateSelection: (Set<Uri>) -> Unit = {},
    onToggleSelect: (Uri) -> Unit = {},
    // ── Feature 3: preview button (null = hidden) ────────────────────────────
    onPreviewImage: ((Int) -> Unit)? = null,
    onImageClick: (Int) -> Unit = {}
) {
    // Accumulates scale delta between column-count snaps (Feature 2)
    var zoomAccumulator by remember { mutableStateOf(1f) }

    val currentSelectedUris by rememberUpdatedState(selectedUris)
    val currentImages by rememberUpdatedState(images)

    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var initialSelection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var autoScrollSpeed by remember { mutableStateOf(0f) }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScrollSpeed)
                delay(16)
            }
        }
    }

    val animatedCellPadding by animateDpAsState(
        targetValue = when {
            columnCount <= 2 -> 3.dp
            columnCount <= 3 -> 2.dp
            else -> 1.dp
        },
        animationSpec = tween(AppConfig.GridResizeDuration, easing = AppConfig.StandardEasing),
        label = "cellPadding"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .animateContentSize(tween(AppConfig.GridResizeDuration))
            // ── Feature 2: pinch gesture detection ───────────────────────────
            // Runs alongside the grid's own scroll gesture.
            // Only consumes events when 2+ fingers are detected so 1-finger
            // scroll is passed through to LazyVerticalGrid unchanged.
            .pointerInput(columnCount, onColumnCountChange) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                zoomAccumulator *= zoom
                                event.changes.forEach { it.consume() }
                                when {
                                    zoomAccumulator > 1.25f && columnCount > 2 -> {
                                        onColumnCountChange(columnCount - 1)
                                        zoomAccumulator = 1f
                                    }
                                    zoomAccumulator < 0.80f && columnCount < 6 -> {
                                        onColumnCountChange(columnCount + 1)
                                        zoomAccumulator = 1f
                                    }
                                }
                            }
                        } else {
                            zoomAccumulator = 1f
                        }
                    } while (event.changes.any { it.pressed })
                    zoomAccumulator = 1f
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val index = gridState.getIndexAtPosition(offset)
                        if (index != null && index < currentImages.size) {
                            dragStartIndex = index
                            initialSelection = currentSelectedUris
                            val uri = currentImages[index]
                            val newSelection = if (uri in initialSelection) {
                                initialSelection - uri
                            } else {
                                initialSelection + uri
                            }
                            onUpdateSelection(newSelection)
                        }
                    },
                    onDrag = { change, _ ->
                        val startIndex = dragStartIndex ?: return@detectDragGesturesAfterLongPress
                        val currentIndex = gridState.getIndexAtPosition(change.position)
                        val y = change.position.y
                        val viewportHeight = size.height
                        val threshold = 100.dp.toPx()
                        val maxScrollSpeed = 20.dp.toPx()
                        if (y < threshold) {
                            autoScrollSpeed = -((threshold - y) / threshold) * maxScrollSpeed
                        } else if (y > viewportHeight - threshold) {
                            autoScrollSpeed = ((y - (viewportHeight - threshold)) / threshold) * maxScrollSpeed
                        } else {
                            autoScrollSpeed = 0f
                        }
                        if (currentIndex != null && currentIndex < currentImages.size) {
                            val isAdding = currentImages[startIndex] !in initialSelection
                            val rangeUris = currentImages.filterIndexed { index, _ ->
                                isIndexSelected(index, startIndex, currentIndex, columnCount)
                            }.toSet()
                            val newSelection = if (isAdding) {
                                initialSelection + rangeUris
                            } else {
                                initialSelection - rangeUris
                            }
                            onUpdateSelection(newSelection)
                        }
                    },
                    onDragEnd = {
                        dragStartIndex = null
                        autoScrollSpeed = 0f
                    },
                    onDragCancel = {
                        dragStartIndex = null
                        autoScrollSpeed = 0f
                    }
                )
            }
    ) {
        LazyVerticalGrid(
            state = gridState,
            // Feature 2: Fixed column count (replaces Adaptive) so pinch is precise
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp) // Tight padding for small cells
        ) {
            itemsIndexed(images, key = { _, uri -> uri.toString() }) { index, uri ->
                val isSelected = uri in selectedUris

                // Each cell is a Box so we can layer overlay elements on top of the image
                Box(
                    modifier = Modifier
                        .padding(animatedCellPadding)
                        .aspectRatio(1f) // Square cell — image always fills correctly
                ) {
                    // ── Image ─────────────────────────────────────────────────
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                if (isSelecting) {
                                    // Feature 1: tapping while selecting toggles the image
                                    onToggleSelect(uri)
                                } else {
                                    // Normal mode: open full screen
                                    onImageClick(index)
                                }
                            },
                        contentScale = ContentScale.Crop, // Always fill the square cell
                    )

                    // ── Feature 1: selection overlay ──────────────────────────
                    if (isSelecting) {
                        if (isSelected) {
                            // Dark overlay to visually indicate the image is chosen
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f))
                            )
                            // Filled checkmark in the top-left corner
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(AppConfig.SelectionIconSize)
                            )
                        } else {
                            // Empty circle in the top-left corner for unselected images
                            Icon(
                                imageVector = Icons.Default.RadioButtonUnchecked,
                                contentDescription = "Not selected",
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(AppConfig.SelectionIconSize)
                            )
                        }
                    }

                    // ── Feature 3: preview button ─────────────────────────────
                    // Shown on every cell when selecting AND cells are large enough
                    // (onPreviewImage is set to null by the parent when cells are too small)
                    if (onPreviewImage != null) {
                        IconButton(
                            onClick = { onPreviewImage(index) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = "Preview image",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ImageGridPreview() {
    val mockImages = List(10) {
        "android.resource://com.example.gallery/drawable/ic_launcher_background".toUri()
    }
    MaterialTheme {
        Surface {
            ImageGrid(
                images = mockImages,
                gridState = rememberLazyGridState()
            )
        }
    }
}

fun LazyGridState.getIndexAtPosition(hitPoint: Offset): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
        val offset = itemInfo.offset
        val size = itemInfo.size
        hitPoint.x >= offset.x && hitPoint.x <= offset.x + size.width &&
        hitPoint.y >= offset.y && hitPoint.y <= offset.y + size.height
    }?.index
}

private fun isIndexSelected(
    index: Int,
    startIndex: Int,
    currentIndex: Int,
    @Suppress("UNUSED_PARAMETER") columnCount: Int
): Boolean {
    val lo = min(startIndex, currentIndex)
    val hi = max(startIndex, currentIndex)
    return index in lo..hi
}
