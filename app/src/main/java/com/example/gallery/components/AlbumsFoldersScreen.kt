package com.example.gallery.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(albumsViewModel: AlbumsViewModel, allNames: List<String>) {
    FoldersScreen(
        viewModel = albumsViewModel,
        allNames = allNames,
        folderGridHeader = {
            Text(
                text = "Albums",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp)
            )
        }
    )
}
