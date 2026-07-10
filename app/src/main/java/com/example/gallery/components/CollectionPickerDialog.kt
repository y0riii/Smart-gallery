package com.example.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.gallery.db.previews.CollectionPreview

@Composable
fun CollectionPickerDialog(
    collections: List<CollectionPreview>,
    favoriteCollectionIds: Set<Long> = emptySet(),
    onDismiss: () -> Unit,
    onSelect: (CollectionPreview) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Add to album") },
        text = {
            if (collections.isEmpty()) {
                Text(
                    "No albums yet. Create one from the Albums tab first.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val sortedFavorites = remember(collections, favoriteCollectionIds) {
                    collections.filter { it.id in favoriteCollectionIds }
                        .sortedBy { it.name.lowercase() }
                }
                val sortedRest = remember(collections, favoriteCollectionIds) {
                    collections.filter { it.id !in favoriteCollectionIds }
                        .sortedBy { it.name.lowercase() }
                }

                val listState = rememberLazyListState()
                val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScrollbar(listState, scrollbarColor)
                        .padding(end = 12.dp)
                ) {
                    if (sortedFavorites.isNotEmpty()) {
                        item {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                        items(sortedFavorites) { collection ->
                            CollectionRow(collection, onSelect)
                        }
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    items(sortedRest) { collection ->
                        CollectionRow(collection, onSelect)
                    }
                }
            }
        }
    )
}

@Composable
private fun CollectionRow(
    collection: CollectionPreview,
    onSelect: (CollectionPreview) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(collection) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 4.dp
): Modifier = composed {
    drawWithContent {
        drawContent()
        val layoutInfo = state.layoutInfo
        val totalItemsCount = layoutInfo.totalItemsCount
        if (totalItemsCount > 0) {
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            val visibleItemsCount = visibleItemsInfo.size
            if (visibleItemsCount < totalItemsCount) {
                val firstVisibleItem = visibleItemsInfo.firstOrNull() ?: return@drawWithContent
                val lastVisibleItem = visibleItemsInfo.lastOrNull() ?: return@drawWithContent
                
                val viewportHeight = size.height
                val firstVisibleIndex = firstVisibleItem.index
                
                val avgItemHeight = visibleItemsInfo.map { it.size }.average().toFloat()
                val totalHeightEstimate = avgItemHeight * totalItemsCount
                
                val firstItemOffset = firstVisibleItem.offset
                val currentScrollEstimate = (firstVisibleIndex * avgItemHeight) - firstItemOffset
                
                val thumbHeight = (viewportHeight / totalHeightEstimate) * viewportHeight
                val minThumbHeight = 16.dp.toPx()
                val actualThumbHeight = thumbHeight.coerceAtLeast(minThumbHeight)
                
                val trackHeight = viewportHeight - actualThumbHeight
                val totalScrollableRange = totalHeightEstimate - viewportHeight
                val thumbOffset = if (totalScrollableRange > 0) {
                    (currentScrollEstimate / totalScrollableRange) * trackHeight
                } else {
                    0f
                }
                
                val widthPx = width.toPx()
                val cornerRadiusPx = widthPx / 2
                
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width - widthPx, thumbOffset.coerceIn(0f, trackHeight)),
                    size = Size(widthPx, actualThumbHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            }
        }
    }
}
