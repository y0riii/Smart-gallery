package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.PeopleViewModel

@Composable
fun PeopleFoldersScreen(
    peopleViewModel: PeopleViewModel,
    allNames: List<String>,
    onAddToCollection: (Set<Uri>) -> Unit = {},
    onRecluster: () -> Unit = {}
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showReclusterDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    // Feature 4: derive the sorted name list directly from the ViewModel's already-sorted folders
    // (sorted by PersonFolderRepository: real names A-Z first, #p1/#p2… last).
    // We do NOT use remember() here so the list is always fresh when the folders state changes.
    val sortedNames = peopleViewModel.folders.map { it.name }

    // Feature 5: build a map from person name → thumbnail URI so the @mention dropdown can show
    // a small person photo next to each name. No remember() for the same freshness reason.
    val nameThumbnails = peopleViewModel.folders.associate { it.name to it.insideFolderThumbnail }

    FoldersScreen(
        viewModel = peopleViewModel,
        allNames = sortedNames,           // Feature 4: sorted list replaces the raw allNames
        onRenameClick = {
            showRenameDialog = true
        },
        emptyStateIcon = Icons.Default.Group,
        emptyStateTitle = "No people found",
        emptyStateDescription = "People will start showing here after the app finishes processing your pictures.",
        folderGridHeader = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "People",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Recluster",
                    modifier = Modifier
                        .clickable { showReclusterDialog = true }
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        additionalSelectionActions = {
            val isMergeEnabled = peopleViewModel.selectedFolderBucketIds.size >= 2
            IconButton(
                onClick = { showMergeDialog = true },
                enabled = isMergeEnabled
            ) {
                Icon(
                    imageVector = Icons.Default.CallMerge,
                    contentDescription = "Merge selected folders",
                    tint = if (isMergeEnabled) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                )
            }
        },
        // Every person folder is renameable (it just updates the person's name).
        onRenameFolder = { bucketId, newName -> peopleViewModel.renameFolder(bucketId, newName) },
        nameThumbnails = nameThumbnails,   // Feature 5: avatar map for the @mention dropdown
        onAddToCollection = onAddToCollection
    )

    val selectedFolder = peopleViewModel.selectedFolder
    if (showRenameDialog && selectedFolder != null) {
        CustomDialog(
            placeholder = "Name",
            currentText = selectedFolder.name,
            confirmText = "Rename",
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                peopleViewModel.renameFolder(selectedFolder.bucketId, newName)
                showRenameDialog = false
            }
        )
    }

    if (showMergeDialog) {
        CustomDialog(
            placeholder = "New Folder Name",
            currentText = "",
            confirmText = "Merge",
            onDismiss = { showMergeDialog = false },
            onConfirm = { newName ->
                peopleViewModel.mergeSelectedFolders(newName)
                showMergeDialog = false
            }
        )
    }

    if (showReclusterDialog) {
        AlertDialog(
            onDismissRequest = { showReclusterDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRecluster()
                        showReclusterDialog = false
                    }
                ) {
                    Text("Recluster")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReclusterDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Recluster People") },
            text = { Text("This will re-run face clustering from scratch using Chinese Whispers. Custom names you've set will be preserved where possible, but person groups may change. Continue?") }
        )
    }
}
