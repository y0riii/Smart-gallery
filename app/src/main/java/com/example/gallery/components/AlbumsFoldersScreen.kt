package com.example.gallery.components

import androidx.compose.runtime.Composable
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(albumsViewModel: AlbumsViewModel, allNames: List<String>) {
    FoldersScreen(
        viewModel = albumsViewModel,
        allNames = allNames
    )
}
