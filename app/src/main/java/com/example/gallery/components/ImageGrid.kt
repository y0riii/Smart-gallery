package com.example.gallery.components

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.example.gallery.ui.theme.AppConfig
import com.example.gallery.utils.isVideoUri
import com.example.gallery.utils.rememberVideoThumbnail
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGrid(
    images: List<Uri>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    /** Optional map of URI → timestamp (ms). When provided, date headers are shown. */
    timestamps: Map<Uri, Long> = emptyMap(),
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
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }

    val currentSelectedUris by rememberUpdatedState(selectedUris)
    val currentImages by rememberUpdatedState(images)
    val currentColumnCount by rememberUpdatedState(columnCount)
    val currentOnColumnCountChange by rememberUpdatedState(onColumnCountChange)

    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var initialSelection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var isDragSelecting by remember { mutableStateOf(false) }

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

    LazyVerticalGrid(
        userScrollEnabled = !isDragSelecting,
        state = gridState,
        // Feature 2: Fixed column count (replaces Adaptive) so pinch is precise
        columns = GridCells.Fixed(columnCount),
        modifier = modifier
            .fillMaxSize()
            // ── Feature 2: pinch gesture detection ───────────────────────────
            // Runs alongside the grid's own scroll gesture.
            // Only consumes events when 2+ fingers are detected so 1-finger
            // scroll is passed through to LazyVerticalGrid unchanged.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                zoomAccumulator *= zoom
                                event.changes.forEach { it.consume() }
                                val cols = currentColumnCount
                                when {
                                    zoomAccumulator > 1.25f && cols > 2 -> {
                                        currentOnColumnCountChange(cols - 1)
                                        zoomAccumulator = 1f
                                    }

                                    zoomAccumulator < 0.80f && cols < 6 -> {
                                        currentOnColumnCountChange(cols + 1)
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
                        isDragSelecting = true
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
                        autoScrollSpeed = if (y < threshold) {
                            -((threshold - y) / threshold) * maxScrollSpeed
                        } else if (y > viewportHeight - threshold) {
                            ((y - (viewportHeight - threshold)) / threshold) * maxScrollSpeed
                        } else {
                            0f
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
                        isDragSelecting = false
                    },
                    onDragCancel = {
                        dragStartIndex = null
                        autoScrollSpeed = 0f
                        isDragSelecting = false
                    }
                )
            },
        contentPadding = PaddingValues(
            start = 2.dp,
            top = 2.dp,
            end = 2.dp,
            bottom = if (isSelecting) {
                val navBarPadding =
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                80.dp + navBarPadding
            } else {
                2.dp
            }
        )
    ) {
        // Build groups: if timestamps are provided, group images by calendar date
        if (timestamps.isNotEmpty()) {
            val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
            // Compute the date string for each image (normalised to midnight)
            val dayOf: (Uri) -> String = { uri ->
                val ts = timestamps[uri] ?: 0L
                dateFormat.format(Date(ts))
            }

            // Group consecutive images under the same date label, preserving overall order
            data class Group(val label: String, val items: List<Pair<Int, Uri>>)
            val groups = mutableListOf<Group>()
            var currentLabel = ""
            var currentItems = mutableListOf<Pair<Int, Uri>>()
            images.forEachIndexed { idx, uri ->
                val label = dayOf(uri)
                if (label != currentLabel) {
                    if (currentItems.isNotEmpty()) groups.add(Group(currentLabel, currentItems))
                    currentLabel = label
                    currentItems = mutableListOf()
                }
                currentItems.add(idx to uri)
            }
            if (currentItems.isNotEmpty()) groups.add(Group(currentLabel, currentItems))

            for (group in groups) {
                // Full-width date header
                item(key = "header_${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = group.label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                // Media cells for this date group
                itemsIndexed(
                    group.items,
                    key = { _, pair -> pair.second.toString() }
                ) { _, pair ->
                    val (index, uri) = pair
                    MediaCell(
                        uri = uri,
                        index = index,
                        isSelected = uri in selectedUris,
                        isSelecting = isSelecting,
                        animatedCellPadding = animatedCellPadding,
                        dragStartIndex = dragStartIndex,
                        onToggleSelect = onToggleSelect,
                        onImageClick = onImageClick,
                        onPreviewImage = onPreviewImage
                    )
                }
            }
        } else {
            // No timestamps: flat grid without headers (search results, albums, etc.)
            itemsIndexed(images, key = { _, uri -> uri.toString() }) { index, uri ->
                MediaCell(
                    uri = uri,
                    index = index,
                    isSelected = uri in selectedUris,
                    isSelecting = isSelecting,
                    animatedCellPadding = animatedCellPadding,
                    dragStartIndex = dragStartIndex,
                    onToggleSelect = onToggleSelect,
                    onImageClick = onImageClick,
                    onPreviewImage = onPreviewImage
                )
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

/** A single square media cell (image or video thumbnail) with selection and preview overlays. */
@Composable
private fun MediaCell(
    uri: Uri,
    index: Int,
    isSelected: Boolean,
    isSelecting: Boolean,
    animatedCellPadding: androidx.compose.ui.unit.Dp,
    dragStartIndex: Int?,
    onToggleSelect: (Uri) -> Unit,
    onImageClick: (Int) -> Unit,
    onPreviewImage: ((Int) -> Unit)?
) {
    Box(
        modifier = Modifier
            .padding(animatedCellPadding)
            .aspectRatio(1f)
    ) {
        val isVideo = uri.isVideoUri()
        val videoThumbnail = if (isVideo) rememberVideoThumbnail(uri) else null

        val context = LocalContext.current
        val imageRequest = remember(uri, videoThumbnail) {
            ImageRequest.Builder(context)
                .data(if (isVideo) videoThumbnail else uri)
                .size(Size(256, 256))
                .memoryCacheKey(uri.toString() + "_thumb")
                .diskCacheKey(uri.toString() + "_thumb")
                .build()
        }

        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    if (dragStartIndex != null) return@clickable
                    if (isSelecting) {
                        onToggleSelect(uri)
                    } else {
                        onImageClick(index)
                    }
                },
            contentScale = ContentScale.Crop,
        )

        // ── Selection overlay ─────────────────────────────────────────────
        if (isSelecting) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                )
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

        // ── Video badge ───────────────────────────────────────────────────
        if (uri.isVideoUri()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ── Preview button ────────────────────────────────────────────────
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
