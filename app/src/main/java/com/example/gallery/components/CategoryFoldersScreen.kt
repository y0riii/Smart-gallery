package com.example.gallery.components

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.CategoryViewModel

@Composable
fun CategoryFoldersScreen(categoryViewModel: CategoryViewModel, allNames: List<String>) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = categoryViewModel,
        allNames = allNames,
        canSort = true,
        folderGridHeader = {
            CreateButton { category -> categoryViewModel.createCategory(category) }
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
}
