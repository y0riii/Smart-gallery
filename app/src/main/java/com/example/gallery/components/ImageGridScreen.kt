package com.example.gallery.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gallery.ui.theme.AppConfig

// Key used for storing the column count across app sessions
private const val PREFS_NAME = "gallery_prefs"
private const val PREFS_KEY_COLUMNS = "grid_column_count"
private const val DEFAULT_COLUMNS = 3
private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

// Hide preview button when cells are this small (too small to show the icon usefully)
private const val PREVIEW_HIDE_THRESHOLD = 5

@Composable
fun ImageGridScreen(
    images: List<Uri>,
    onDelete: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    fullScreenIndex: Int?,
    onIndexChanged: (Int?) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    // Relevance-separator boundary for semantic search results (null = no separator).
    relevantCount: Int? = null,
    // ── Feature 1: selection state passed in from the parent ─────────────────
    selectedUris: Set<Uri> = emptySet(),
    isSelecting: Boolean = false,
    onUpdateSelection: (Set<Uri>) -> Unit = {},
    onToggleSelect: (Uri) -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onShareSelected: () -> Unit = {},
    onAddToCollectionSelected: (() -> Unit)? = null,
    // Shown only when exactly one item is selected; opens its Info panel (size / path / albums).
    onInfoSelected: (() -> Unit)? = null,
    // When non-null, the delete dialog also offers "remove from this folder" (keeps the files on the
    // device) using this label; the callback removes the selection's membership. Null = device-only.
    removeFromFolderLabel: String? = null,
    onRemoveFromFolder: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // ── Feature 2: column count persisted in SharedPreferences ──────────────
    var columnCount by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREFS_KEY_COLUMNS, DEFAULT_COLUMNS)
        )
    }

    // Feature 1: confirmation dialog state before bulk delete
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {

        if (fullScreenIndex == null) {
            // Feature 3: show preview button only when selecting AND cells are big enough
            val showPreviewButton = isSelecting && columnCount < PREVIEW_HIDE_THRESHOLD

            ImageGrid(
                images = images,
                gridState = gridState,
                modifier = Modifier.fillMaxSize(),
                // Relevance separator (semantic folder search); null for date/OCR/no-prompt.
                relevantCount = relevantCount,
                // Feature 2: controlled column count
                columnCount = columnCount,
                onColumnCountChange = { newCount ->
                    columnCount = newCount
                    // Persist the new column count for next app launch
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(PREFS_KEY_COLUMNS, newCount).apply()
                },
                // Feature 1: selection params
                selectedUris = selectedUris,
                isSelecting = isSelecting,
                onUpdateSelection = onUpdateSelection,
                onToggleSelect = onToggleSelect,
                onImageClick = { index -> onIndexChanged(index) },
                // Feature 3: null hides the button; non-null shows it on every cell
                onPreviewImage = if (showPreviewButton) { index -> onIndexChanged(index) } else null
            )
        }

        // Normal full-screen viewer (not preview mode)
        fullScreenIndex?.let { index ->
            FullScreenImage(
                images = images,
                initialIndex = index,
                onClose = { onIndexChanged(null) },
                onDelete = onDelete,
                // Feature 3: if we are in selection mode, this is a preview → hide share/delete
                isPreviewMode = isSelecting
            )

            BackHandler {
                onIndexChanged(null)
            }
        }

        // ── Feature 1: selection action bar shown at the bottom ──────────────
        AnimatedVisibility(
            visible = isSelecting && fullScreenIndex == null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(
                    AppConfig.SelectionBarDuration,
                    easing = AppConfig.EmphasizedEasing
                )
            ) + fadeIn(tween(AppConfig.SelectionBarDuration)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(AppConfig.SelectionBarDuration)
            ) + fadeOut(tween(AppConfig.SelectionBarDuration)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                // Number of selected images
                Text(
                    text = "${selectedUris.size} selected",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                )

                // Info: enabled only when exactly one item is selected (size / path / albums); greyed
                // out and non-clickable while multiple are selected.
                if (onInfoSelected != null) {
                    val infoEnabled = selectedUris.size == 1
                    IconButton(onClick = onInfoSelected, enabled = infoEnabled) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Info",
                            tint = if (infoEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Share selected images
                // Context is available here via LocalContext for the share intent
                IconButton(onClick = onShareSelected) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Add to collection
                if (onAddToCollectionSelected != null) {
                    IconButton(onClick = onAddToCollectionSelected) {
                        Icon(
                            Icons.Default.LibraryAdd,
                            contentDescription = "Add to collection",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Delete selected images (shows confirmation dialog first)
                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Cancel selection mode
                TextButton(onClick = onCancelSelection) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // ── Feature 1: bulk delete confirmation dialog ───────────────────────────
    if (showDeleteConfirmDialog) {
        val count = selectedUris.size
        val plural = if (count == 1) "" else "s"
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete $count item$plural?") },
            text = {
                Text(
                    if (removeFromFolderLabel != null)
                        "Remove them from this album (they stay on your device), or delete them from " +
                            "your device? Deleting from the device can't be undone."
                    else
                        "This permanently deletes them from your device and can't be undone."
                )
            },
            // Up to three stacked choices: remove-from-folder (optional), delete-from-device, cancel.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (removeFromFolderLabel != null && onRemoveFromFolder != null) {
                        TextButton(onClick = {
                            showDeleteConfirmDialog = false
                            onRemoveFromFolder()
                        }) { Text(removeFromFolderLabel) }
                    }
                    TextButton(onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteSelected() // triggers ViewModel's deleteSelectedImages()
                    }) {
                        Text(
                            if (removeFromFolderLabel != null) "Delete from device" else "Delete",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    // Feature 1: pressing Back while selecting cancels selection mode
    if (isSelecting && fullScreenIndex == null) {
        BackHandler { onCancelSelection() }
    }
}
