package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.gallery.viewModels.CollectionsViewModel

@Composable
fun CollectionsFoldersScreen(
    collectionsViewModel: CollectionsViewModel,
    allNames: List<String>,
    onAddToCollection: (Set<Uri>) -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = collectionsViewModel,
        allNames = allNames,
        canSort = false,
        emptyStateIcon = Icons.Default.CollectionsBookmark,
        emptyStateTitle = "Create Collections",
        emptyStateDescription = "Create your own collections and add specific photos and videos to them manually.",
        emptyStateAction = {
            Button(onClick = { showCreateDialog = true }) {
                Text("Create Collection")
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
                    text = "Collections",
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
        selectedFolderActions = {
            Text(
                text = "Delete",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable {
                        showDeleteDialog = true
                    }
                    .padding(8.dp)
            )
        },
        onAddToCollection = onAddToCollection
    )

    if (showDeleteDialog) {
        val selectedFolder = collectionsViewModel.selectedFolder
        if (selectedFolder != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            collectionsViewModel.deleteCollection()
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
                title = { Text("Delete Collection") },
                text = { Text("Are you sure you want to delete this collection? Media items will NOT be deleted.") }
            )
        }
    }

    if (showCreateDialog) {
        CustomDialog(
            placeholder = "Collection name",
            confirmText = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                collectionsViewModel.createCollection(name)
                showCreateDialog = false
            }
        )
    }
}
