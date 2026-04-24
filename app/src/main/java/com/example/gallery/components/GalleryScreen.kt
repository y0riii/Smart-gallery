package com.example.gallery.components

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val allNames by viewModel.allNames.collectAsState()
    var selectedImageIndex by remember { mutableStateOf<Int?>(null) }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeletionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel.intentSenderRequest) {
        viewModel.intentSenderRequest?.let {
            intentSenderLauncher.launch(it)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GalleryContent(
            images = viewModel.images,
            isSearching = viewModel.isSearching,
            statusText = viewModel.statusText,
            allNames = allNames,
            onSearch = { prompt, useClip ->
                viewModel.search(prompt, useClip)
            },
            onImageClick = { index ->
                selectedImageIndex = index
            }
        )

        selectedImageIndex?.let { index ->
            FullScreenImage(
                images = viewModel.images,
                initialIndex = index,
                onClose = { selectedImageIndex = null },
                onDelete = { uri ->
                    viewModel.deleteImage(uri)
                }
            )

            BackHandler {
                selectedImageIndex = null
            }
        }
    }
}

@Composable
fun GalleryContent(
    images: List<Uri>,
    isSearching: Boolean,
    statusText: String,
    allNames: List<String>,
    onSearch: (String, Boolean) -> Unit,
    onImageClick: (Int) -> Unit
) {
    val gridState = rememberLazyGridState()

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            isSearching = isSearching,
            onSearch = onSearch,
            allNames = allNames
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
            modifier = Modifier.weight(1f),
            onImageClick = onImageClick
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
                allNames = emptyList(),
                onSearch = { _, _ -> },
                onImageClick = {}
            )
        }
    }
}
