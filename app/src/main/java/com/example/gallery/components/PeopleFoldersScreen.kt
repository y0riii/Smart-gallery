package com.example.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gallery.viewModels.PeopleViewModel

@Composable
fun PeopleFoldersScreen(peopleViewModel: PeopleViewModel, allNames: List<String>) {
    var showRenameDialog by remember { mutableStateOf(false) }

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
        modifier = Modifier.clickable {
            showRenameDialog = true
        },
        folderGridHeader = {
            Text(
                text = "People",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 4.dp)
            )
        },
        nameThumbnails = nameThumbnails   // Feature 5: avatar map for the @mention dropdown
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
