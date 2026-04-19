package com.example.gallery.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenImage(
    images: List<Uri>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (Uri) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Track the zoom state of the current page to disable paging when zoomed
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    // Confirmation dialog state
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !isCurrentPageZoomed
        ) { pageIndex ->
            val uri = images[pageIndex]
            
            // Use Animatable for smooth zoom transitions
            val scale = remember { Animatable(1f) }
            val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

            // Update the global zoomed state if this is the current page
            LaunchedEffect(scale.value) {
                if (pagerState.currentPage == pageIndex) {
                    isCurrentPageZoomed = scale.value > 1.05f
                }
            }

            // Reset zoom when swiping away
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage != pageIndex) {
                    scale.snapTo(1f)
                    offset.snapTo(Offset.Zero)
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                val boxWidth = maxWidth
                val boxHeight = maxHeight

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var lastTapTime = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val now = System.currentTimeMillis()
                                
                                // Double tap detection
                                val isDoubleTap = now - lastTapTime < 300
                                if (isDoubleTap) {
                                    scope.launch {
                                        if (scale.value > 1.05f) {
                                            launch { scale.animateTo(1f) }
                                            launch { offset.animateTo(Offset.Zero) }
                                        } else {
                                            // Zoom in to 3x on double tap
                                            launch { scale.animateTo(3f) }
                                        }
                                    }
                                }
                                lastTapTime = now

                                do {
                                    val event = awaitPointerEvent()
                                    if (isDoubleTap) {
                                        // Ignore movements if this is the second tap of a double tap
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        if (scale.value > 1f || zoomChange != 1f) {
                                            // Consume events only if already zoomed or starting a zoom
                                            event.changes.forEach { it.consume() }

                                            // Only apply manual transform if no animation is currently running
                                            if (!scale.isRunning && !offset.isRunning) {
                                                scope.launch {
                                                    val newScale = (scale.value * zoomChange).coerceIn(1f, 3f)

                                                    // Calculate max offset to prevent panning into "black space"
                                                    val maxX = (size.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                                                    val maxY = (size.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                                                    
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
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Full screen image",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale.value,
                                scaleY = scale.value,
                                translationX = offset.value.x,
                                translationY = offset.value.y
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            
            Box(modifier = Modifier.weight(1f))

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
                showDeleteConfirmation = true
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }

        if (showDeleteConfirmation) {
            ModalBottomSheet(
                onDismissRequest = { showDeleteConfirmation = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Delete Image?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This will permanently remove the image from your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    showDeleteConfirmation = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    showDeleteConfirmation = false
                                    val currentUri = images[pagerState.currentPage]
                                    onDelete(currentUri)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
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
