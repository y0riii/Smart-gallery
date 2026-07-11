package com.example.gallery.components

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gallery.GalleryService
import com.example.gallery.viewModels.AlbumsViewModel

@Composable
fun AlbumsFoldersScreen(
    albumsViewModel: AlbumsViewModel,
    allNames: List<String>,
    onAddToCollection: (Set<Uri>) -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = albumsViewModel,
        allNames = allNames,
        albumSections = true,
        // Merge: combine 2+ selected albums into a new user album (deduped). Disabled unless 2+ are
        // selected and none is the reserved "Detected Duplicates" album.
        additionalSelectionActions = {
            val selectedFolders = albumsViewModel.selectedFolderBucketIds
                .mapNotNull { id -> albumsViewModel.folders.find { it.bucketId == id } }
            val isMergeEnabled = selectedFolders.size >= 2 && selectedFolders.none {
                it.name.equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true)
            }
            IconButton(onClick = { showMergeDialog = true }, enabled = isMergeEnabled) {
                Icon(
                    imageVector = Icons.Default.CallMerge,
                    contentDescription = "Merge selected albums",
                    tint = if (isMergeEnabled) MaterialTheme.colorScheme.primary
                    else Color.Gray.copy(alpha = 0.5f)
                )
            }
        },
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
        // Only user-created albums can be renamed; device folders are read-only, and the reserved
        // auto "Detected Duplicates" album is off-limits too.
        onRenameFolder = { bucketId, newName -> albumsViewModel.renameAlbum(bucketId, newName) },
        isFolderRenameable = {
            it.isUserAlbum && !it.name.equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true)
        },
        onAddToCollection = onAddToCollection
    )

    if (showCreateDialog) {
        val context = LocalContext.current
        CustomDialog(
            placeholder = "Album name",
            confirmText = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                if (name.trim().equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true)) {
                    // Reserved for the auto duplicates album — tell the user and keep the dialog open.
                    Toast.makeText(
                        context,
                        "\"${GalleryService.DUPLICATES_ALBUM_NAME}\" is reserved — please choose another name.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    albumsViewModel.createAlbum(name)
                    showCreateDialog = false
                }
            }
        )
    }

    if (showMergeDialog) {
        val context = LocalContext.current
        CustomDialog(
            placeholder = "Merged album name",
            currentText = "",
            confirmText = "Merge",
            onDismiss = { showMergeDialog = false },
            onConfirm = { name ->
                if (name.trim().equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true)) {
                    Toast.makeText(
                        context,
                        "\"${GalleryService.DUPLICATES_ALBUM_NAME}\" is reserved — please choose another name.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    albumsViewModel.mergeAlbums(
                        albumsViewModel.selectedFolderBucketIds.toList(),
                        name
                    )
                    showMergeDialog = false
                }
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
