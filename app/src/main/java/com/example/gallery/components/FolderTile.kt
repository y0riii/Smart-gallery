package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.folders.FolderItem
import com.example.gallery.ui.theme.AppConfig
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import com.example.gallery.utils.isVideoUri
import com.example.gallery.utils.rememberVideoThumbnail
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderTile(
    folder: FolderItem,
    modifier: Modifier = Modifier,
    isSelecting: Boolean = false,
    isSelected: Boolean = false,
    isFavorite: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFavoriteClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(AppConfig.FolderTileCornerRadius),
        border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(
            defaultElevation = AppConfig.FolderTileElevation,
            pressedElevation = AppConfig.FolderTileElevationPressed
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // 2x2 thumbnail grid (no overlay — clean mosaic)
            Box(
                Modifier.clip(
                    RoundedCornerShape(
                        topStart = AppConfig.FolderTileCornerRadius,
                        topEnd = AppConfig.FolderTileCornerRadius
                    )
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    val thumbs = folder.thumbnailUris
                    Column(Modifier.weight(1f)) {
                        ThumbCell(
                            thumbs.getOrNull(0), Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        ThumbCell(
                            thumbs.getOrNull(2), Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(1.dp))
                    Column(Modifier.weight(1f)) {
                        ThumbCell(
                            thumbs.getOrNull(1), Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        ThumbCell(
                            thumbs.getOrNull(3), Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }

                if (isSelecting) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                }

                // Favorite Star Button (Modern semi-transparent round background overlay)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { onFavoriteClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Toggle favorite",
                        tint = if (isFavorite) Color(0xFFFFD700) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Caption row: name + photo count on the left,
            // Feature 1: person thumbnail (square, rounded corners) on the right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppConfig.FolderTileInnerPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Name + count take all remaining space
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${folder.photoCount} photos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Feature 1: square person thumbnail with rounded corners pinned to the right
                if (folder.insideFolderThumbnail != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    AsyncImage(
                        model = folder.insideFolderThumbnail,
                        contentDescription = "Person thumbnail",
                        modifier = Modifier
                            .size(AppConfig.FolderPersonThumbSize)
                            .clip(RoundedCornerShape(AppConfig.FolderPersonThumbCornerRadius))
                            .border(
                                BorderStroke(
                                    AppConfig.AvatarOutlineWidth,
                                    MaterialTheme.colorScheme.surface
                                ),
                                RoundedCornerShape(AppConfig.FolderPersonThumbCornerRadius)
                            ),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbCell(uri: Uri?, modifier: Modifier) {
    if (uri != null) {
        val isVideo = uri.isVideoUri()
        val videoThumbnail = if (isVideo) rememberVideoThumbnail(uri) else null

        AsyncImage(
            model = if (isVideo) videoThumbnail else uri,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.fillMaxSize()) // empty placeholder
    }
}
