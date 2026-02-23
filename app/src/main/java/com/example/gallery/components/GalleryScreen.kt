package com.example.gallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {

    val images = viewModel.images
    val isSearching = viewModel.isSearching
    val statusText = viewModel.statusText

    val gridState = rememberLazyGridState()

    LaunchedEffect(images) {
        if (images.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            isSearching = viewModel.isSearching,
            onSearch = { prompt, useClip ->
                viewModel.search(prompt, useClip)
            }
        )

        if (isSearching || statusText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isSearching) {
                    CircularProgressIndicator()
                } else {
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ImageGrid(
            images = images,
            gridState = gridState
        )
    }
}