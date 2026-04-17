package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    GalleryContent(
        images = viewModel.images,
        isSearching = viewModel.isSearching,
        statusText = viewModel.statusText,
        onSearch = { prompt, useClip ->
            viewModel.search(prompt, useClip)
        }
    )
}

@Composable
fun GalleryContent(
    images: List<Uri>,
    isSearching: Boolean,
    statusText: String,
    onSearch: (String, Boolean) -> Unit
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(images) {
        if (images.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            isSearching = isSearching,
            onSearch = onSearch
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
            gridState = gridState,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true, name = "Gallery - Success State")
@Composable
fun GalleryScreenPreview() {
    MaterialTheme {
        Surface {
            GalleryContent(
                images = List(10) {
                    "android.resource://com.example.gallery/drawable/ic_launcher_background".toUri()
                },
                isSearching = false,
                statusText = "Showing results for \"placeholder images\"",
                onSearch = { _, _ -> }
            )
        }
    }
}

@Preview(showBackground = true, name = "Gallery - Searching State")
@Composable
fun GalleryScreenSearchingPreview() {
    MaterialTheme {
        Surface {
            GalleryContent(
                images = emptyList(),
                isSearching = true,
                statusText = "Searching...",
                onSearch = { _, _ -> }
            )
        }
    }
}
