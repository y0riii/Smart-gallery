package com.example.gallery.components

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.gallery.GalleryService
import com.example.gallery.ui.theme.AppConfig
import com.example.gallery.viewModels.GalleryViewModel

// Shared SharedPreferences constants (same file as ImageGridScreen)
private const val GALLERY_PREFS_NAME = "gallery_prefs"
private const val GALLERY_PREFS_KEY_COLUMNS = "grid_column_count"
private const val GALLERY_DEFAULT_COLUMNS = 3
private const val GALLERY_MIN_COLUMNS = 2
private const val GALLERY_MAX_COLUMNS = 6

// Hide preview button below this column count threshold
private const val GALLERY_PREVIEW_HIDE_THRESHOLD = 5

@Composable
fun GalleryScreen(viewModel: GalleryViewModel, onAddToCollection: (Set<Uri>) -> Unit = {}) {
    val allNames by viewModel.allNames.collectAsState()
    // Feature 5: person name → thumbnail URI for the @mention dropdown avatars
    val nameThumbnails by viewModel.nameThumbnails.collectAsState()
    val fullScreenIndex = viewModel.fullScreenIndex
    val gridState = rememberLazyGridState()
    val context = LocalContext.current

    // Live indexing progress (0f..1f while a pass runs, null when idle) — used to tell the user that
    // sparse search results are because indexing is still in flight, not because search is broken.
    val indexingProgress by GalleryService.progress.collectAsState()

    // App version for the Settings → About row (read once from the package manager).
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    // ── Feature 2: column count persisted in SharedPreferences ──────────────
    var columnCount by remember {
        mutableStateOf(
            context.getSharedPreferences(GALLERY_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(GALLERY_PREFS_KEY_COLUMNS, GALLERY_DEFAULT_COLUMNS)
        )
    }

    // Feature 1: confirmation dialog state before bulk delete
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    // Settings dialog (Arabic OCR toggle, etc.)
    var showSettings by remember { mutableStateOf(false) }
    // Remove-duplicates: info/confirmation dialog that explains the action before scanning.
    var showDuplicatesInfo by remember { mutableStateOf(false) }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeletionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.intentSenderVersion }
            .collect { version ->
                if (version > 0) {
                    viewModel.intentSenderRequest?.let {
                        intentSenderLauncher.launch(it)
                    }
                }
            }
    }

    // Scroll to top when the ViewModel signals it (after images are fully updated)
    LaunchedEffect(viewModel.shouldScrollToTop) {
        if (viewModel.shouldScrollToTop) {
            gridState.scrollToItem(0)
            viewModel.shouldScrollToTop = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        if (fullScreenIndex == null) {
            // Feature 3: show preview button only when selecting AND cells are big enough
            val showPreviewButton =
                viewModel.isSelecting && columnCount < GALLERY_PREVIEW_HIDE_THRESHOLD

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gallery",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showDuplicatesInfo = true }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Group duplicates")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
                SearchBar(
                    prompt = viewModel.prompt,
                    onPromptChange = { viewModel.prompt = it },
                    useClip = viewModel.useClip,
                    onUseClipChange = { viewModel.useClip = it },
                    searchVideos = viewModel.searchVideos,
                    onSearchVideosChange = {
                        viewModel.searchVideos = it
                        // Re-run immediately so results update without pressing Go again.
                        if (viewModel.isSearchActive) viewModel.search()
                    },
                    showVideoToggle = true,
                    fromDate = viewModel.fromDate,
                    onFromDateChange = { viewModel.fromDate = it },
                    toDate = viewModel.toDate,
                    onToDateChange = { viewModel.toDate = it },
                    isSearching = viewModel.isSearching,
                    onSearch = {
                        viewModel.search()
                    },
                    onClear = {
                        viewModel.clearSearch()
                    },
                    allNames = allNames,
                    searchActive = viewModel.isSearchActive,
                    // Feature 5: pass thumbnails so avatars appear in the @mention dropdown
                    nameThumbnails = nameThumbnails
                )

                if (viewModel.isSearching) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // Grid, or a premium empty state when there's nothing to show. A search/date
                // filter being active changes the empty message from "no photos" to "no matches".
                //
                // Keyed off the committed-search flag (isSearchActive), NOT the live input fields:
                // the displayed results and their formatting must stay put when the user empties the
                // search box — only pressing Clear resets the view. Deriving this from prompt.text
                // would make the grid re-group by date the moment the last character is deleted.
                val isFilterActive = viewModel.isSearchActive

                // Result count for an active search. For a scored (semantic) search we report the
                // number of RELEVANT matches (above the threshold); otherwise the total.
                if (isFilterActive && !viewModel.isSearching && viewModel.images.isNotEmpty()) {
                    val relevant = viewModel.relevantCount
                    Text(
                        text = if (relevant != null) "$relevant relevant" else "${viewModel.images.size} results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ImageGrid(
                        images = viewModel.images,
                        gridState = gridState,
                        modifier = Modifier.fillMaxSize(),
                        // Date headers only when browsing all media. During a search/filter the
                        // results are ordered by relevance (not date), so grouping them by date
                        // would both reorder them away from most-relevant-first and produce
                        // duplicate header keys — so we show a flat, relevance-ordered grid instead.
                        timestamps = if (isFilterActive) emptyMap() else viewModel.mediaTimestamps,
                        // Relevance separator for semantic search results (null otherwise).
                        relevantCount = viewModel.relevantCount,
                        // Feature 2: controlled column count
                        columnCount = columnCount,
                        onColumnCountChange = { newCount ->
                            columnCount = newCount
                            // Persist new column count for next app launch
                            context.getSharedPreferences(GALLERY_PREFS_NAME, Context.MODE_PRIVATE)
                                .edit { putInt(GALLERY_PREFS_KEY_COLUMNS, newCount) }
                        },
                        // Feature 1: selection params from ViewModel
                        selectedUris = viewModel.selectedUris,
                        isSelecting = viewModel.isSelecting,
                        onUpdateSelection = { viewModel.updateSelection(it) },
                        onToggleSelect = { uri -> viewModel.toggleSelection(uri) },
                        onImageClick = { index -> viewModel.openFullScreen(index) },
                        // Feature 3: null hides the button
                        onPreviewImage = if (showPreviewButton) { index ->
                            viewModel.openFullScreen(index)
                        } else null
                    )

                    if (viewModel.images.isEmpty() && !viewModel.isSearching) {
                        // While a pass is running, tell the user sparse results are due to indexing
                        // still being in progress (not a broken search) — this is the exact confusion
                        // that made search "return nothing" on a slow device mid-index.
                        val indexingPct = indexingProgress?.let { (it * 100).toInt() }
                        EmptyState(
                            icon = if (isFilterActive) Icons.Default.SearchOff else Icons.Default.PhotoLibrary,
                            title = if (isFilterActive) "No matches" else "No photos yet",
                            subtitle = when {
                                isFilterActive && indexingPct != null ->
                                    "Still indexing your library ($indexingPct%). Search results will " +
                                        "improve as it finishes."
                                isFilterActive -> "Try a different search or clear the filters."
                                indexingPct != null ->
                                    "Indexing your library ($indexingPct%). Your photos will appear here as it runs."
                                else -> "Your photos will appear here once they've been processed."
                            }
                        )
                    }
                }
            }

            // Feature 1: selection action bar
            AnimatedVisibility(
                visible = viewModel.isSelecting,
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
                    Text(
                        text = "${viewModel.selectedUris.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp)
                    )
                    // Info: enabled only when exactly one item is selected (size / path / albums);
                    // greyed out and non-clickable while multiple are selected.
                    val infoEnabled = viewModel.selectedUris.size == 1
                    IconButton(
                        onClick = { viewModel.showMediaInfo(viewModel.selectedUris.first()) },
                        enabled = infoEnabled
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Info",
                            tint = if (infoEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { viewModel.shareSelectedImages(context) }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { onAddToCollection(viewModel.selectedUris) }) {
                        Icon(
                            Icons.Default.LibraryAdd,
                            contentDescription = "Add to collection",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Full-screen viewer
        fullScreenIndex?.let { index ->
            FullScreenImage(
                images = viewModel.images,
                initialIndex = index,
                onClose = { viewModel.closeFullScreen() },
                onDelete = { uri -> viewModel.deleteImage(uri) },
                // Feature 3: read-only when opened as a preview during selection
                isPreviewMode = viewModel.isSelecting
            )

            BackHandler {
                viewModel.closeFullScreen()
            }
        }
    }

    // Feature 1: bulk delete confirmation dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${viewModel.selectedUris.size} photo(s)?") },
            text = { Text("Are you sure you want to delete the selected photos? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedImages()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Settings dialog (Arabic OCR opt-in)
    if (showSettings) {
        SettingsDialog(
            themeMode = viewModel.themeMode,
            onThemeModeChange = { viewModel.updateThemeMode(it) },
            autoReclusterEnabled = viewModel.autoReclusterEnabled,
            onAutoReclusterChange = { viewModel.updateAutoReclusterEnabled(it) },
            arabicOcrEnabled = viewModel.arabicOcrEnabled,
            onArabicOcrChange = { viewModel.updateArabicOcrEnabled(it) },
            appVersion = appVersion,
            onRescan = {
                viewModel.rescanLibrary()
                showSettings = false
                Toast.makeText(context, "Re-scanning your library…", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSettings = false }
        )
    }

    // ── Group duplicates: explain → scan + group → result ──────────────────────
    // Step 1: explain what the action does, before doing anything. Non-destructive: it only gathers
    // duplicates into an album for review, nothing is deleted.
    if (showDuplicatesInfo) {
        AlertDialog(
            onDismissRequest = { showDuplicatesInfo = false },
            title = { Text("Group duplicates") },
            text = {
                Text(
                    "This scans your whole gallery for duplicate photos and videos — exact copies of " +
                        "the same file — and gathers every version of each (the original and all its " +
                        "copies, side by side) into an album called \"Detected Duplicates\" so you can " +
                        "review them. Nothing is deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDuplicatesInfo = false
                    viewModel.groupDuplicates()
                }) { Text("Group duplicates") }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicatesInfo = false }) { Text("Cancel") }
            }
        )
    }

    // Step 2: progress while scanning + grouping (hashing files can take a moment on large libraries).
    if (viewModel.isGroupingDuplicates) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Grouping duplicates") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Checking your photos and videos…")
                }
            },
            confirmButton = { }
        )
    }

    // Step 3: show the result — how many were grouped (or none found).
    viewModel.duplicatesGroupedCount?.let { count ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicatesResult() },
            title = { Text(if (count == 0) "No duplicates found" else "Duplicates grouped") },
            text = {
                Text(
                    if (count == 0) {
                        "Your gallery has no duplicate photos or videos."
                    } else {
                        "Grouped $count duplicate item${if (count == 1) "" else "s"} into the " +
                            "\"Detected Duplicates\" album (in the Albums tab). Nothing was deleted — " +
                            "review them there and delete any copies you don't want."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDuplicatesResult() }) { Text("OK") }
            }
        )
    }

    // Single-item Info panel (size / path / albums), shown from the selection bar.
    viewModel.mediaInfo?.let { info ->
        MediaInfoDialog(info = info, onDismiss = { viewModel.dismissMediaInfo() })
    }

    // Feature 1: Back cancels selection mode (only when not in full screen)
    if (viewModel.isSelecting && fullScreenIndex == null) {
        BackHandler { viewModel.clearSelection() }
    }
}
