package com.example.gallery.components


import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ImageGrid(images: List<Uri>, gridState: LazyGridState) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(128.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp)
    ) {
        items(images, key = { it.toString() }) { result ->
            AsyncImage(
                model = result,
                contentDescription = "Image result",
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f),
                contentScale = ContentScale.Fit
            )
        }
    }
}