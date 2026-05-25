package com.example.gallery.components

import androidx.compose.runtime.Composable
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(
    albumsViewModel: AlbumsViewModel,
    fullScreenIndex: Int?,
    onIndexChanged: (Int?) -> Unit,
) {
    FoldersScreen(
        viewModel = albumsViewModel,
        fullScreenIndex = fullScreenIndex,
        onIndexChanged = onIndexChanged
    )
}
