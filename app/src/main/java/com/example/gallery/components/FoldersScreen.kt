package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.FolderItem
import com.example.gallery.FolderRepository
import kotlinx.coroutines.launch

@Composable
fun FoldersScreen(onFolderClick: (FolderItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FolderRepository(context) }

    var folders by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            folders = repo.getFolders()
            isLoading = false
        }
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(folders, key = { it.bucketId }) { folder ->
            FolderTile(folder = folder, onClick = { onFolderClick(folder) })
        }
    }
}

@Composable
private fun FolderTile(folder: FolderItem, onClick: () -> Unit) {
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
            // 2×2 thumbnail grid
            Row(Modifier
                .fillMaxWidth()
                .aspectRatio(1f)) {
                val thumbs = folder.thumbnailUris
                Column(Modifier.weight(1f)) {
                    ThumbCell(thumbs.getOrNull(0), Modifier
                        .weight(1f)
                        .fillMaxWidth())
                    ThumbCell(thumbs.getOrNull(2), Modifier
                        .weight(1f)
                        .fillMaxWidth())
                }
                Spacer(Modifier.width(1.dp))
                Column(Modifier.weight(1f)) {
                    ThumbCell(thumbs.getOrNull(1), Modifier
                        .weight(1f)
                        .fillMaxWidth())
                    ThumbCell(thumbs.getOrNull(3), Modifier
                        .weight(1f)
                        .fillMaxWidth())
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
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier.fillMaxSize()) // empty placeholder
    }
}