package com.example.gallery.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.gallery.viewModels.CategoryViewModel

@Composable
fun CategoryFoldersScreen(
    categoryViewModel: CategoryViewModel, fullScreenIndex: Int?,
    onIndexChanged: (Int?) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectedFolder = categoryViewModel.selectedFolder

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        categoryViewModel.onDeletionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(categoryViewModel.intentSenderRequest) {
        categoryViewModel.intentSenderRequest?.let {
            intentSenderLauncher.launch(it)
        }
    }

    if (categoryViewModel.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (selectedFolder == null) {
        Column(modifier = Modifier.fillMaxSize()) {

            CreateButton { category -> categoryViewModel.createCategory(category) }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categoryViewModel.folders, key = { it.bucketId }) { folder ->
                    FolderTile(
                        folder = folder,
                        onClick = { categoryViewModel.loadFolder(folder.bucketId) })
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (selectedFolder.insideFolderThumbnail != null) {
                        AsyncImage(
                            model = selectedFolder.insideFolderThumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .width(64.dp)
                                .aspectRatio(1f),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Text(
                        text = selectedFolder.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // 🔴 Delete Category Button
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable {
                            showDeleteDialog = true
                        }
                        .padding(8.dp)
                )
            }

            ImageGridScreen(
                images = categoryViewModel.images,
                onDelete = { categoryViewModel.deleteImage(it) },
                fullScreenIndex = fullScreenIndex,
                onIndexChanged = onIndexChanged
            )
        }

        BackHandler { categoryViewModel.clear() }

        if (showDeleteDialog) {

            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            categoryViewModel.deleteCategory()
                            showDeleteDialog = false
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                },
                text = { Text("Are you sure?") }
            )
        }
    }
}