package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.example.gallery.ui.theme.AppConfig
import coil3.compose.AsyncImage
import com.example.gallery.folders.FolderItem
import com.example.gallery.SortMode
import com.example.gallery.viewModels.FoldersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: FoldersViewModel,
    allNames: List<String>,
    modifier: Modifier = Modifier,
    canSort: Boolean = false,
    showDates: Boolean = true,
    folderGridHeader: @Composable ColumnScope.() -> Unit = {},
    selectedFolderActions: @Composable RowScope.(FolderItem) -> Unit = {}
) {
    val selectedFolder = viewModel.selectedFolder
    val fullScreenIndex = viewModel.fullScreenIndex
    val gridState = rememberLazyGridState()
    val context = LocalContext.current

    var showSortMenu by remember { mutableStateOf(false) }

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

    // Scroll to top logic after rendering
    LaunchedEffect(viewModel.images, viewModel.isLoading) {
        if (!viewModel.isLoading && viewModel.shouldScrollToTop) {
            gridState.scrollToItem(0)
            viewModel.shouldScrollToTop = false
        }
    }

    AnimatedContent(
        targetState = selectedFolder,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(
                    initialOffsetX = { it / 4 },
                    animationSpec = tween(AppConfig.ScreenEnterDuration, easing = AppConfig.EmphasizedEasing)
                ) + fadeIn(tween(AppConfig.ScreenEnterDuration)) togetherWith fadeOut(tween(AppConfig.ScreenEnterDuration))
            } else {
                fadeIn(tween(AppConfig.ScreenEnterDuration)) togetherWith slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(AppConfig.ScreenExitDuration, easing = AppConfig.EmphasizedEasing)
                ) + fadeOut(tween(AppConfig.ScreenExitDuration))
            }
        },
        label = "FolderNavigation"
    ) { currentFolder ->
        if (currentFolder == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                folderGridHeader()

                if (viewModel.isLoading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.folders.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PhotoAlbum, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("No items yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(AppConfig.FolderGridPadding),
                        verticalArrangement = Arrangement.spacedBy(AppConfig.FolderGridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(AppConfig.FolderGridSpacing)
                    ) {
                        items(viewModel.folders, key = { it.bucketId }) { folder ->
                            FolderTile(
                                folder = folder,
                                onClick = { viewModel.loadFolder(folder.bucketId) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
            // Folder Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (currentFolder.insideFolderThumbnail != null) {
                        AsyncImage(
                            model = currentFolder.insideFolderThumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .width(AppConfig.FolderThumbnailSize)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Text(
                        text = currentFolder.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = modifier
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    selectedFolderActions(currentFolder)
                    
                    if (canSort) {
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
                allNames = allNames,
                showDates = showDates
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
                    modifier = Modifier.fillMaxSize(),
                    // Feature 1: wire selection state from DeletableViewModel base class
                    selectedUris = viewModel.selectedUris,
                    isSelecting = viewModel.isSelecting,
                    onLongPress = { uri -> viewModel.startSelection(uri) },
                    onToggleSelect = { uri -> viewModel.toggleSelection(uri) },
                    onCancelSelection = { viewModel.clearSelection() },
                    onDeleteSelected = { viewModel.deleteSelectedImages() },
                    // Feature 1: share — pass context available in this composable scope
                    onShareSelected = { viewModel.shareSelectedImages(context) }
                )
                
                if (viewModel.isLoading) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        BackHandler { viewModel.clearSelectedFolder() }
    }
    }

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
