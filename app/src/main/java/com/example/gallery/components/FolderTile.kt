package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gallery.folders.FolderItem

@Composable
fun FolderTile(folder: FolderItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            0.5.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column {
            // 2x2 thumbnail grid
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
            // Name + count
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.photoCount} photos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThumbCell(uri: Uri?, modifier: Modifier) {
    if (uri != null) {
        ThumbnailImage(
            uri = uri,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            sizePx = 256
        )
    } else {
        Box(modifier.fillMaxSize()) // empty placeholder
    }
}
