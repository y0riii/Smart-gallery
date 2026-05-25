package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(
    albumsViewModel: AlbumsViewModel, fullScreenIndex: Int?,
    onIndexChanged: (Int?) -> Unit,
) {
    val selectedFolder = albumsViewModel.selectedFolder

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        albumsViewModel.onDeletionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(albumsViewModel.intentSenderRequest) {
        albumsViewModel.intentSenderRequest?.let {
            intentSenderLauncher.launch(it)
        }
    }

    if (albumsViewModel.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (selectedFolder == null) {

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(albumsViewModel.folders, key = { it.bucketId }) { folder ->
                FolderTile(
                    folder = folder,
                    onClick = { albumsViewModel.loadFolder(folder.bucketId) })
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (selectedFolder.insideFolderThumbnail != null) {
                        AsyncImage(
                            model = selectedFolder.insideFolderThumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .width(64.dp)
                                .aspectRatio(1f),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Text(
                        text = selectedFolder.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            ImageGridScreen(
                images = albumsViewModel.images,
                onDelete = { albumsViewModel.deleteImage(it) },
                fullScreenIndex = fullScreenIndex,
                onIndexChanged = onIndexChanged
            )
        }

        BackHandler { albumsViewModel.clear() }
    }
}