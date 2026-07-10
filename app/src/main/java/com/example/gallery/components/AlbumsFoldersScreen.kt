package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(
    albumsViewModel: AlbumsViewModel,
    allNames: List<String>,
    onAddToCollection: (Set<Uri>) -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = albumsViewModel,
        allNames = allNames,
        albumSections = true,
        folderGridHeader = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Albums",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Create",
                    modifier = Modifier
                        .clickable { showCreateDialog = true }
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        // Delete is offered only for user-created albums; device folders are read-only.
        selectedFolderActions = { folder ->
            if (folder.isUserAlbum) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { showDeleteDialog = true }
                        .padding(8.dp)
                )
            }
        },
        onAddToCollection = onAddToCollection
    )

    if (showCreateDialog) {
        CustomDialog(
            placeholder = "Album name",
            confirmText = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                albumsViewModel.createAlbum(name)
                showCreateDialog = false
            }
        )
    }

    if (showDeleteDialog) {
        val selectedFolder = albumsViewModel.selectedFolder
        if (selectedFolder != null && selectedFolder.isUserAlbum) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            albumsViewModel.deleteSelectedAlbum()
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
                title = { Text("Delete Album") },
                text = { Text("Are you sure you want to delete this album? The photos and videos in it will NOT be deleted from your device.") }
            )
        }
    }
}
