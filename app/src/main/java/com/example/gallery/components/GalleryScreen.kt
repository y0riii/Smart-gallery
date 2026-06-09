package com.example.gallery.components

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.GalleryViewModel
import kotlinx.coroutines.launch

// Shared SharedPreferences constants (same file as ImageGridScreen)
private const val GALLERY_PREFS_NAME = "gallery_prefs"
private const val GALLERY_PREFS_KEY_COLUMNS = "grid_column_count"
private const val GALLERY_DEFAULT_COLUMNS = 3
private const val GALLERY_MIN_COLUMNS = 2
private const val GALLERY_MAX_COLUMNS = 6
// Hide preview button below this column count threshold
private const val GALLERY_PREVIEW_HIDE_THRESHOLD = 5

@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val allNames by viewModel.allNames.collectAsState()
    val fullScreenIndex = viewModel.fullScreenIndex
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── Feature 2: column count persisted in SharedPreferences ──────────────
    var columnCount by remember {
        mutableStateOf(
            context.getSharedPreferences(GALLERY_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(GALLERY_PREFS_KEY_COLUMNS, GALLERY_DEFAULT_COLUMNS)
        )
    }

    // Feature 1: confirmation dialog state before bulk delete
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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

        if (fullScreenIndex == null) {
            // Feature 3: show preview button only when selecting AND cells are big enough
            val showPreviewButton = viewModel.isSelecting && columnCount < GALLERY_PREVIEW_HIDE_THRESHOLD

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
                    isSearching = viewModel.isSearching,
                    onSearch = {
                        coroutineScope.launch { gridState.scrollToItem(0) }
                        viewModel.search()
                    },
                    onClear = {
                        coroutineScope.launch { gridState.scrollToItem(0) }
                        viewModel.clearSearch()
                    },
                    allNames = allNames
                )

                if (viewModel.isSearching || viewModel.statusText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (viewModel.isSearching) {
                            CircularProgressIndicator()
                        } else {
                            Text(viewModel.statusText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                ImageGrid(
                    images = viewModel.images,
                    gridState = gridState,
                    modifier = Modifier.weight(1f),
                    // Feature 2: controlled column count
                    columnCount = columnCount,
                    onColumnCountChange = { newCount ->
                        columnCount = newCount
                        // Persist new column count for next app launch
                        context.getSharedPreferences(GALLERY_PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(GALLERY_PREFS_KEY_COLUMNS, newCount).apply()
                    },
                    // Feature 1: selection params from ViewModel
                    selectedUris = viewModel.selectedUris,
                    isSelecting = viewModel.isSelecting,
                    onLongPress = { uri -> viewModel.startSelection(uri) },
                    onToggleSelect = { uri -> viewModel.toggleSelection(uri) },
                    onImageClick = { index -> viewModel.openFullScreen(index) },
                    // Feature 3: null hides the button
                    onPreviewImage = if (showPreviewButton) { index ->
                        viewModel.openFullScreen(index)
                    } else null
                )
            }

            // Feature 1: selection action bar
            if (viewModel.isSelecting) {
                BottomAppBar(
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = "${viewModel.selectedUris.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp)
                    )
                    IconButton(onClick = { viewModel.shareSelectedImages(context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share selected")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text("Cancel")
                    }
                }
            }
        }

        // Full-screen viewer
        fullScreenIndex?.let { index ->
            FullScreenImage(
                images = viewModel.images,
                initialIndex = index,
                onClose = { viewModel.closeFullScreen() },
                onDelete = { uri -> viewModel.deleteImage(uri) },
                // Feature 3: read-only when opened as a preview during selection
                isPreviewMode = viewModel.isSelecting
            )

            BackHandler {
                viewModel.closeFullScreen()
            }
        }
    }

    // Feature 1: bulk delete confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${viewModel.selectedUris.size} photo(s)?") },
            text = { Text("Are you sure you want to delete the selected photos? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedImages()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Feature 1: Back cancels selection mode (only when not in full screen)
    if (viewModel.isSelecting && fullScreenIndex == null) {
        BackHandler { viewModel.clearSelection() }
    }
}
