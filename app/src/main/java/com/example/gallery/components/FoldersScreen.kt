package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
<<<<<<< Updated upstream
import androidx.compose.material3.CircularProgressIndicator
=======
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
>>>>>>> Stashed changes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.folders.FolderItem
import com.example.gallery.viewModels.FoldersViewModel
<<<<<<< Updated upstream
=======
import kotlinx.coroutines.launch
import java.util.Date
>>>>>>> Stashed changes

@Composable
fun FoldersScreen(
    viewModel: FoldersViewModel,
    allNames: List<String>,
    modifier: Modifier = Modifier,
    folderGridHeader: @Composable ColumnScope.() -> Unit = {},
    selectedFolderActions: @Composable RowScope.(FolderItem) -> Unit = {}
) {
    val selectedFolder = viewModel.selectedFolder
    val fullScreenIndex = viewModel.fullScreenIndex
<<<<<<< Updated upstream
=======
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    var showSortMenu by remember { mutableStateOf(false) }
>>>>>>> Stashed changes

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

<<<<<<< Updated upstream
    if (viewModel.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (selectedFolder == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            folderGridHeader()
=======
    // Scroll to top logic after rendering
    LaunchedEffect(viewModel.images, viewModel.isLoading) {
        if (!viewModel.isLoading && viewModel.shouldScrollToTop) {
            gridState.scrollToItem(0)
            viewModel.shouldScrollToTop = false
        }
    }

    if (selectedFolder == null) {
        if (viewModel.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                folderGridHeader()
>>>>>>> Stashed changes

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(viewModel.folders, key = { it.bucketId }) { folder ->
                    FolderTile(
                        folder = folder,
                        onClick = { viewModel.loadFolder(folder.bucketId) }
                    )
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedFolder.insideFolderThumbnail != null) {
                        AsyncImage(
                            model = selectedFolder.insideFolderThumbnail,
                            contentDescription = null,
                            modifier = Modifier
<<<<<<< Updated upstream
                                .width(64.dp)
=======
                                .width(40.dp)
>>>>>>> Stashed changes
                                .aspectRatio(1f),
                            contentScale = ContentScale.Crop,
                        )

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Text(
                        text = selectedFolder.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = modifier
                    )
                }

<<<<<<< Updated upstream
                selectedFolderActions(selectedFolder)
            }

            ImageGridScreen(
                images = viewModel.images,
                onDelete = { viewModel.deleteImage(it) },
                fullScreenIndex = fullScreenIndex,
                onIndexChanged = { index ->
                    if (index == null) {
                        viewModel.closeFullScreen()
                    } else {
                        viewModel.openFullScreen(index)
=======
                Row(verticalAlignment = Alignment.CenterVertically) {
                    selectedFolderActions(selectedFolder)
                    
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sort by relevance") },
                                onClick = {
                                    viewModel.onSortModeChanged(SortMode.RELEVANCE)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by date descending") },
                                onClick = {
                                    viewModel.onSortModeChanged(SortMode.DATE_DESC)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            SearchBar(
                prompt = viewModel.prompt,
                onPromptChange = { viewModel.prompt = it },
                useClip = viewModel.useClip,
                onUseClipChange = { viewModel.useClip = it },
                fromDate = viewModel.fromDate,
                onFromDateChange = { viewModel.onDateRangeChanged(it, viewModel.toDate) },
                toDate = viewModel.toDate,
                onToDateChange = { viewModel.onDateRangeChanged(viewModel.fromDate, it) },
                isSearching = viewModel.isLoading,
                onSearch = { 
                    viewModel.shouldScrollToTop = true
                    viewModel.applyFilters() 
                },
                onClear = { viewModel.clearSearch() },
                allNames = allNames
            )

            Spacer(modifier = Modifier.height(4.dp))

            Box(modifier = Modifier.weight(1f)) {
                ImageGridScreen(
                    images = viewModel.images,
                    onDelete = { viewModel.deleteImage(it) },
                    fullScreenIndex = fullScreenIndex,
                    onIndexChanged = { index ->
                        if (index == null) {
                            viewModel.closeFullScreen()
                        } else {
                            viewModel.openFullScreen(index)
                        }
                    },
                    gridState = gridState,
                    modifier = Modifier.fillMaxSize()
                )
                
                if (viewModel.isLoading) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
>>>>>>> Stashed changes
                    }
                }
            )
        }

        BackHandler { viewModel.clearSelectedFolder() }
    }
<<<<<<< Updated upstream
=======

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
>>>>>>> Stashed changes
}
