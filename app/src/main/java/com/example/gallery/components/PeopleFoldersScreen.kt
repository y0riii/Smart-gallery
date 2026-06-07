package com.example.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.gallery.viewModels.PeopleViewModel

@Composable
fun PeopleFoldersScreen(peopleViewModel: PeopleViewModel, allNames: List<String>) {
    var showRenameDialog by remember { mutableStateOf(false) }

    FoldersScreen(
        viewModel = peopleViewModel,
        allNames = allNames,
        modifier = Modifier.clickable {
            showRenameDialog = true
        }
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
}
