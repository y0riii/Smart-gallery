package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.SortMode
import com.example.gallery.viewModels.FoldersViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: FoldersViewModel,
    modifier: Modifier = Modifier,
    folderGridHeader: @Composable ColumnScope.() -> Unit = {},
    selectedFolderActions: @Composable RowScope.(FolderItem) -> Unit = {}
) {
    val selectedFolder = viewModel.selectedFolder
    val fullScreenIndex = viewModel.fullScreenIndex
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
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

    if (selectedFolder == null) {
        if (viewModel.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                folderGridHeader()

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
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Folder Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (selectedFolder.insideFolderThumbnail != null) {
                        AsyncImage(
                            model = selectedFolder.insideFolderThumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .width(48.dp)
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

                    IconButton(onClick = {
                        coroutineScope.launch { gridState.scrollToItem(0) }
                        viewModel.clearSearch()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }

            // Date Filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = viewModel.fromDate?.let { sdf.format(Date(it)) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("From") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showFromPicker = true }) {
                                Icon(Icons.Default.DateRange, null)
                            }
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Box(Modifier.matchParentSize().clickable { showFromPicker = true }) {}
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = viewModel.toDate?.let { sdf.format(Date(it)) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("To") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showToPicker = true }) {
                                Icon(Icons.Default.DateRange, null)
                            }
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Box(Modifier.matchParentSize().clickable { showToPicker = true }) {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                    }
                }
            }
        }

        BackHandler { viewModel.clearSelectedFolder() }
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

    if (showFromPicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = viewModel.fromDate)
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDateRangeChanged(datePickerState.selectedDateMillis, viewModel.toDate)
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onDateRangeChanged(null, viewModel.toDate)
                    showFromPicker = false
                }) { Text("Clear") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showToPicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = viewModel.toDate)
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDateRangeChanged(viewModel.fromDate, datePickerState.selectedDateMillis)
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onDateRangeChanged(viewModel.fromDate, null)
                    showToPicker = false
                }) { Text("Clear") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
