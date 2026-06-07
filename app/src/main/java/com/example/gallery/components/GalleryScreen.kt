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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.gallery.viewModels.GalleryViewModel
import kotlinx.coroutines.launch

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val allNames by viewModel.allNames.collectAsState()
    val fullScreenIndex = viewModel.fullScreenIndex
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

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
            gridState = gridState,
            viewModel = viewModel,
            onSearch = {
                coroutineScope.launch {
                    gridState.scrollToItem(0)
                }
                viewModel.search()
            },
            onClear = {
                coroutineScope.launch {
                    gridState.scrollToItem(0)
                }
                viewModel.clearSearch()
            },
            onImageClick = { index ->
                viewModel.openFullScreen(index)
            }
        )

        fullScreenIndex?.let { index ->
            FullScreenImage(
                images = viewModel.images,
                initialIndex = index,
                onClose = { viewModel.closeFullScreen() },
                onDelete = { uri ->
                    viewModel.deleteImage(uri)
                }
            )

            BackHandler {
                viewModel.closeFullScreen()
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
    gridState: LazyGridState,
    viewModel: GalleryViewModel,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onImageClick: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            prompt = viewModel.prompt,
            onPromptChange = { viewModel.prompt = it },
            useClip = viewModel.useClip,
            onUseClipChange = { viewModel.useClip = it },
            fromDate = viewModel.fromDate,
            onFromDateChange = { viewModel.fromDate = it },
            toDate = viewModel.toDate,
            onToDateChange = { viewModel.toDate = it },
            isSearching = isSearching,
            onSearch = onSearch,
            onClear = onClear,
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
