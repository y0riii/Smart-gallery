package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.SortMode
import com.example.gallery.folders.FolderItem
import com.example.gallery.ui.theme.AppConfig
import com.example.gallery.viewModels.FoldersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: FoldersViewModel,
    allNames: List<String>,
    onRenameClick: (() -> Unit)? = null,
    canSort: Boolean = false,
    showDates: Boolean = true,
    folderGridHeader: @Composable ColumnScope.() -> Unit = {},
    selectedFolderActions: @Composable RowScope.(FolderItem) -> Unit = {},
    // Feature: customizable empty state
    emptyStateIcon: ImageVector = Icons.Default.PhotoAlbum,
    emptyStateTitle: String = "No items yet",
    emptyStateDescription: String? = null,
    emptyStateAction: @Composable (() -> Unit)? = null,
    // Feature 5: optional name→thumbnail map passed down to the SearchBar inside an open folder,
    // so @mention suggestions can display circular person avatars. Defaults to empty.
    nameThumbnails: Map<String, android.net.Uri?> = emptyMap()
) {
    val selectedFolder = viewModel.selectedFolder
    val fullScreenIndex = viewModel.fullScreenIndex
    // Feature 3: two independent grid states so scrolling in the image grid does NOT
    // corrupt the folder-list scroll position (which was the original bug).
    val foldersGridState = rememberLazyGridState()  // for the folders tile grid
    val imagesGridState = rememberLazyGridState()  // for the images inside an open folder
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showSortMenu by remember { mutableStateOf(false) }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeletionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.intentSenderVersion }
            .collect { version ->
                if (version > 0) {
                    viewModel.intentSenderRequest?.let {
                        intentSenderLauncher.launch(it)
                    }
                }
            }
    }

    // Scroll the IMAGE grid to the top when the ViewModel signals it (after filters change).
    // Uses imagesGridState so it never touches the folders-list scroll position.
    LaunchedEffect(viewModel.shouldScrollToTop) {
        if (viewModel.shouldScrollToTop) {
            imagesGridState.scrollToItem(0)
            viewModel.shouldScrollToTop = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = selectedFolder,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(
                    initialOffsetX = { it / 4 },
                    animationSpec = tween(
                        AppConfig.ScreenEnterDuration,
                        easing = AppConfig.EmphasizedEasing
                    )
                ) + fadeIn(tween(AppConfig.ScreenEnterDuration)) togetherWith fadeOut(
                    tween(
                        AppConfig.ScreenEnterDuration
                    )
                )
            } else {
                fadeIn(tween(AppConfig.ScreenEnterDuration)) togetherWith slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(
                        AppConfig.ScreenExitDuration,
                        easing = AppConfig.EmphasizedEasing
                    )
                ) + fadeOut(tween(AppConfig.ScreenExitDuration))
            }
        },
        label = "FolderNavigation"
    ) { currentFolder ->
        if (currentFolder == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                folderGridHeader()

                // Feature 3: foldersGridState naturally retains its scroll position because it
                // is separate from imagesGridState. The LaunchedEffect is a safety net that
                // restores the ViewModel-saved position in case the composable is fully recreated.
                LaunchedEffect(Unit) {
                    foldersGridState.scrollToItem(
                        index = viewModel.savedGridFirstIndex,
                        scrollOffset = viewModel.savedGridFirstScrollOffset
                    )
                }
                if (viewModel.isLoading) {
                    Box(Modifier
                        .weight(1f)
                        .fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.folders.isEmpty()) {
                    Box(Modifier
                        .weight(1f)
                        .fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                emptyStateIcon,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                emptyStateTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (emptyStateDescription != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = emptyStateDescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                            if (emptyStateAction != null) {
                                Spacer(Modifier.height(24.dp))
                                emptyStateAction()
                            }
                        }
                    }
                } else {
                    val voiceFoldersLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                            if (!spokenText.isNullOrBlank()) {
                                viewModel.folderSearchQuery = spokenText
                            }
                        }
                    }

                    TextField(
                        value = viewModel.folderSearchQuery,
                        onValueChange = { viewModel.folderSearchQuery = it },
                        placeholder = { Text("Search folders...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(AppConfig.SearchFieldCornerRadius),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (viewModel.folderSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.folderSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            } else {
                                IconButton(onClick = {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak folder name...")
                                    }
                                    voiceFoldersLauncher.launch(intent)
                                }) {
                                    Icon(Icons.Default.Mic, contentDescription = "Voice search")
                                }
                            }
                        }
                    )

                    if (viewModel.filteredFolders.isEmpty()) {
                        Box(Modifier
                            .weight(1f)
                            .fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "No folders match your search",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = foldersGridState,   // Feature 3: dedicated state, never shared with image grid
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(AppConfig.FolderGridPadding),
                            verticalArrangement = Arrangement.spacedBy(AppConfig.FolderGridSpacing),
                            horizontalArrangement = Arrangement.spacedBy(AppConfig.FolderGridSpacing)
                        ) {
                            items(viewModel.filteredFolders, key = { it.bucketId }) { folder ->
                                val isSelected = viewModel.selectedFolderBucketIds.contains(folder.bucketId)
                                val isFavorite = viewModel.favoriteFolderIds.contains(folder.bucketId)
                                FolderTile(
                                    folder = folder,
                                    isSelecting = viewModel.isSelectingFolders,
                                    isSelected = isSelected,
                                    isFavorite = isFavorite,
                                    onClick = {
                                        if (viewModel.isSelectingFolders) {
                                            viewModel.toggleFolderSelection(folder.bucketId)
                                        } else {
                                            // Feature 3: save foldersGridState position before opening a folder
                                            viewModel.savedGridFirstIndex =
                                                foldersGridState.firstVisibleItemIndex
                                            viewModel.savedGridFirstScrollOffset =
                                                foldersGridState.firstVisibleItemScrollOffset
                                            viewModel.loadFolder(folder.bucketId)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleFolderSelection(folder.bucketId)
                                    },
                                    onFavoriteClick = {
                                        val firstIndex = foldersGridState.firstVisibleItemIndex
                                        val firstOffset = foldersGridState.firstVisibleItemScrollOffset
                                        viewModel.toggleFavoriteFolder(folder.bucketId)
                                        coroutineScope.launch {
                                            foldersGridState.scrollToItem(firstIndex, firstOffset)
                                        }
                                    },
                                    modifier = Modifier.animateItem()
                                )
                            }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
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

                        // Feature: Show edit icon to make it clear the title is renamable
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onRenameClick != null) {
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onRenameClick)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            } else {
                                Modifier
                            }
                        ) {
                            Text(
                                text = currentFolder.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (onRenameClick != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename folder",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        selectedFolderActions(currentFolder)

                        if (canSort) {
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "Sort"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }) {
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
                    onSearch = { viewModel.applyFilters(scrollToTop = true) },
                    onClear = { viewModel.clearSearch() },
                    allNames = allNames,
                    showDates = showDates,
                    // Feature 5: forward the thumbnail map so person avatars appear in the dropdown
                    nameThumbnails = nameThumbnails
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
                        gridState = imagesGridState,   // Feature 3: dedicated state, never shared with folder grid
                        modifier = Modifier.fillMaxSize(),
                        // Feature 1: wire selection state from DeletableViewModel base class
                        selectedUris = viewModel.selectedUris,
                        isSelecting = viewModel.isSelecting,
                        onUpdateSelection = { viewModel.updateSelection(it) },
                        onToggleSelect = { uri -> viewModel.toggleSelection(uri) },
                        onCancelSelection = { viewModel.clearSelection() },
                        onDeleteSelected = { viewModel.deleteSelectedImages() },
                        // Feature 1: share — pass context available in this composable scope
                        onShareSelected = { viewModel.shareSelectedImages(context) }
                    )

                    if (viewModel.isLoading) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            BackHandler(enabled = fullScreenIndex == null && !viewModel.isSelecting) { viewModel.clearSelectedFolder() }
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

    if (viewModel.isSelectingFolders && selectedFolder == null) {
        BackHandler {
            viewModel.clearFolderSelection()
        }
    }

    AnimatedVisibility(
        visible = viewModel.isSelectingFolders && selectedFolder == null,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(
                AppConfig.SelectionBarDuration,
                easing = AppConfig.EmphasizedEasing
            )
        ) + fadeIn(tween(AppConfig.SelectionBarDuration)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(AppConfig.SelectionBarDuration)
        ) + fadeOut(tween(AppConfig.SelectionBarDuration)),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        BottomAppBar(
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = "${viewModel.selectedFolderBucketIds.size} selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )

            var showDeleteConfirmDialog by remember { mutableStateOf(false) }
            val isDeleteEnabled = viewModel.canDeleteFolders

            IconButton(
                onClick = { showDeleteConfirmDialog = true },
                enabled = isDeleteEnabled
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete selected folders",
                    tint = if (isDeleteEnabled) MaterialTheme.colorScheme.error else Color.Gray.copy(alpha = 0.5f)
                )
            }

            if (showDeleteConfirmDialog && isDeleteEnabled) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    title = { Text("Delete ${viewModel.selectedFolderBucketIds.size} folder(s)?") },
                    text = { Text("Are you sure you want to delete the selected folders? The physical photos inside the folders will NOT be deleted from your device, but the folders/data will be removed from the app.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirmDialog = false
                                viewModel.deleteSelectedFolders(context)
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

            Spacer(modifier = Modifier.width(4.dp))

            TextButton(onClick = { viewModel.clearFolderSelection() }) {
                Text("Cancel", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
}
