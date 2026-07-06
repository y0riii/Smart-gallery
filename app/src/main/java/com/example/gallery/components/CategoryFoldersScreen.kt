package com.example.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import com.example.gallery.viewModels.CategoryViewModel

@Composable
fun CategoryFoldersScreen(categoryViewModel: CategoryViewModel, allNames: List<String>) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = categoryViewModel,
        allNames = allNames,
        canSort = true,
        emptyStateIcon = Icons.Default.AutoAwesome,
        emptyStateTitle = "Create AI Albums",
        emptyStateDescription = "Automatically group photos by prompt.\nTry 'gym exercises', 'food', or 'nature'.",
        emptyStateAction = {
            Button(onClick = { showCreateDialog = true }) {
                Text("Create AI Album")
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
                    text = "AI Albums",
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
        }
    )

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

    if (showCreateDialog) {
        CustomDialog(
            placeholder = "Prompt (e.g. gym, food)",
            confirmText = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                categoryViewModel.createCategory(name)
                showCreateDialog = false
            }
        )
    }
}
