package com.example.gallery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun FoldersScreen(onFolderClick: (FolderItem) -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val repo    = remember { FolderRepository(context) }

    var folders   by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            folders   = repo.getFolders()
            isLoading = false
        }
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
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
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column {
            // 2×2 thumbnail grid
            Row(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val thumbs = folder.thumbnailUris
                Column(Modifier.weight(1f)) {
                    ThumbCell(thumbs.getOrNull(0), Modifier.weight(1f).fillMaxWidth())
                    ThumbCell(thumbs.getOrNull(2), Modifier.weight(1f).fillMaxWidth())
                }
                Spacer(Modifier.width(1.dp))
                Column(Modifier.weight(1f)) {
                    ThumbCell(thumbs.getOrNull(1), Modifier.weight(1f).fillMaxWidth())
                    ThumbCell(thumbs.getOrNull(3), Modifier.weight(1f).fillMaxWidth())
                }
            }
            // Name + count
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text  = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text  = "${folder.photoCount} photos",
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