package com.example.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.components.AlbumsFoldersScreen
import com.example.gallery.components.CategoryFoldersScreen
import com.example.gallery.components.GalleryScreen
import com.example.gallery.components.PeopleFoldersScreen
import com.example.gallery.components.PermissionRequestScreen
import com.example.gallery.db.AppDatabase
import com.example.gallery.folders.AlbumsFolderRepository
import com.example.gallery.folders.CategoryFolderRepository
import com.example.gallery.folders.PersonFolderRepository
import com.example.gallery.ui.theme.AppConfig
import com.example.gallery.ui.theme.GalleryTheme
import com.example.gallery.viewModels.AlbumsViewModel
import com.example.gallery.viewModels.CategoryViewModel
import com.example.gallery.viewModels.GalleryViewModel
import com.example.gallery.viewModels.PeopleViewModel
import com.example.gallery.viewModels.factories.AlbumsViewModelFactory
import com.example.gallery.viewModels.factories.CategoryViewModelFactory
import com.example.gallery.viewModels.factories.GalleryViewModelFactory
import com.example.gallery.viewModels.factories.PeopleViewModelFactory
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import com.example.gallery.db.GalleryPeriodicTriggerWorker
import com.example.gallery.db.GalleryIndexerWorker
import com.example.gallery.db.FaceClusteringWorker
import androidx.core.net.toUri
import com.example.gallery.db.previews.CollectionPreview
import com.example.gallery.components.CollectionPickerDialog
import com.example.gallery.components.CollectionsFoldersScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.gallery.components.tutorial.TutorialOverlay
import com.example.gallery.components.tutorial.TutorialPrefs
import com.example.gallery.components.tutorial.TutorialTab
import com.example.gallery.components.tutorial.tutorialContentFor

class MainActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }

    private val galleryService by lazy { GalleryService(applicationContext) }

    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(db.personDao(), galleryService)
    }

    private val peopleViewModel: PeopleViewModel by viewModels {
        PeopleViewModelFactory(
            PersonFolderRepository(db.personDao(), galleryService),
            galleryService
        )
    }

    private val categoryViewModel: CategoryViewModel by viewModels {
        CategoryViewModelFactory(
            CategoryFolderRepository(db.categoryDao(), galleryService),
            galleryService
        )
    }

    private val albumsViewModel: AlbumsViewModel by viewModels {
        AlbumsViewModelFactory(
            AlbumsFolderRepository(applicationContext, galleryService),
            galleryService
        )
    }

    private val collectionsViewModel: com.example.gallery.viewModels.CollectionsViewModel by viewModels {
        com.example.gallery.viewModels.factories.CollectionsViewModelFactory(
            com.example.gallery.folders.CollectionFolderRepository(db.collectionDao(), galleryService),
            db.collectionDao(),
            galleryService
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBatteryOptimizationExemptionOnce()
        setContent {
            GalleryTheme {
                GalleryApp(galleryViewModel, peopleViewModel, categoryViewModel, albumsViewModel, collectionsViewModel, galleryService)
            }
        }
    }

    /**
     * Requests battery optimization exemption exactly once (on first app open).
     * Uses a SharedPreferences flag so the system dialog is never shown again
     * after the user has dismissed or accepted it.
     */
    private fun requestBatteryOptimizationExemptionOnce() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("battery_opt_asked", false)
        if (alreadyAsked) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        }

        // Mark as asked regardless of user choice — we never ask again
        prefs.edit { putBoolean("battery_opt_asked", true) }
    }

    override fun onStart() {
        super.onStart()
        if (hasAnyPermission(this)) {
            GalleryIndexerWorker.isPaused = false
            galleryService.startIndexingWorkManager(force = true)
            scheduleBackgroundIndexing()
        }
    }

    private fun hasAnyPermission(context: android.content.Context): Boolean {
        return getPermissionsToRequest().any { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun scheduleBackgroundIndexing() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val indexingRequest =
            PeriodicWorkRequestBuilder<GalleryPeriodicTriggerWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "GalleryIndexing_Periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            indexingRequest
        )

        // Cancel the weekly clustering work if it was previously scheduled
        WorkManager.getInstance(applicationContext).cancelUniqueWork("GalleryClustering_Weekly")
    }
}

private fun getPermissionsToRequest(): Array<String> {
    val permissions = mutableListOf<String>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions += Manifest.permission.READ_MEDIA_IMAGES
        permissions += Manifest.permission.READ_MEDIA_VIDEO
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        permissions += Manifest.permission.READ_EXTERNAL_STORAGE
    }

    return permissions.toTypedArray()
}

@Composable
fun GalleryApp(
    galleryViewModel: GalleryViewModel,
    peopleViewModel: PeopleViewModel,
    categoryViewModel: CategoryViewModel,
    albumsViewModel: AlbumsViewModel,
    collectionsViewModel: com.example.gallery.viewModels.CollectionsViewModel,
    galleryService: GalleryService
) {

    var hasPermission by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val currentTab = if (hasPermission) pagerState.currentPage else 0

    // ── First-time tutorial ──────────────────────────────────────────────────
    // Show a one-time explainer overlay the first time the user lands on each navbar tab.
    // "Seen" state is persisted per-tab in TutorialPrefs, so each overlay appears exactly once.
    // Gated on hasPermission so tips (which describe searching/browsing) only appear once the
    // pager is actually usable. TutorialTab order matches the pager page indices (0..4).
    val tutorialContext = LocalContext.current
    val tutorialPrefs = remember { TutorialPrefs(tutorialContext) }
    var activeTutorial by remember { mutableStateOf<TutorialTab?>(null) }
    LaunchedEffect(hasPermission, currentTab) {
        if (!hasPermission) return@LaunchedEffect
        val tab = TutorialTab.entries.getOrNull(currentTab) ?: return@LaunchedEffect
        if (!tutorialPrefs.hasSeen(tab)) activeTutorial = tab
    }

    val permissionsToRequest = remember { getPermissionsToRequest() }

    val allNames by galleryViewModel.allNames.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it }
    }

    val progress by GalleryService.progress.collectAsState()

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            galleryViewModel.onPermissionGranted()
            peopleViewModel.onPermissionGranted()
            categoryViewModel.onPermissionGranted()
            albumsViewModel.onPermissionGranted()
            collectionsViewModel.onPermissionGranted()
        }
    }

    val isFullScreen = when {
        !hasPermission -> galleryViewModel.fullScreenIndex != null
        currentTab == 0 -> galleryViewModel.fullScreenIndex != null
        currentTab == 1 -> peopleViewModel.fullScreenIndex != null
        currentTab == 2 -> categoryViewModel.fullScreenIndex != null
        currentTab == 3 -> albumsViewModel.fullScreenIndex != null
        currentTab == 4 -> collectionsViewModel.fullScreenIndex != null
        else -> false
    }

    val isSelecting = when {
        !hasPermission -> galleryViewModel.isSelecting
        currentTab == 0 -> galleryViewModel.isSelecting
        currentTab == 1 -> peopleViewModel.isSelecting
        currentTab == 2 -> categoryViewModel.isSelecting
        currentTab == 3 -> albumsViewModel.isSelecting
        currentTab == 4 -> collectionsViewModel.isSelecting
        else -> false
    }

    val isSelectingFolders = when {
        !hasPermission -> false
        currentTab == 1 -> peopleViewModel.isSelectingFolders
        currentTab == 2 -> categoryViewModel.isSelectingFolders
        currentTab == 3 -> albumsViewModel.isSelectingFolders
        currentTab == 4 -> collectionsViewModel.isSelectingFolders
        else -> false
    }

    val isFolderOpen = when (currentTab) {
        1 -> peopleViewModel.selectedFolder != null
        2 -> categoryViewModel.selectedFolder != null
        3 -> albumsViewModel.selectedFolder != null
        4 -> collectionsViewModel.selectedFolder != null
        else -> false
    }

    val pagerScrollEnabled = !isFullScreen && !isSelecting && !isFolderOpen && !isSelectingFolders

    // Collection picker dialog state
    var showCollectionPicker by remember { mutableStateOf(false) }
    var pendingUrisForCollection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var availableCollections by remember { mutableStateOf<List<CollectionPreview>>(emptyList()) }

    val onAddToCollection: (Set<Uri>) -> Unit = { uris ->
        pendingUrisForCollection = uris
        coroutineScope.launch {
            availableCollections = collectionsViewModel.getCollectionsList()
            showCollectionPicker = true
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        bottomBar = {
            // Hide the nav bar + progress on the permission screen (nothing to navigate yet).
            if (hasPermission && !isFullScreen && !isSelecting && !isSelectingFolders) {
                Column {
                    progress?.let {
                        LinearProgressIndicator(
                            progress = { it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val navColors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home", textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = navColors,
                            alwaysShowLabel = true
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            },
                            icon = { Icon(Icons.Default.People, contentDescription = "People") },
                            label = { Text("People", textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = navColors,
                            alwaysShowLabel = true
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            },
                            icon = {
                                Icon(
                                    Icons.Default.CollectionsBookmark,
                                    contentDescription = "AI Albums"
                                )
                            },
                            label = { Text("AI Albums", textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = navColors,
                            alwaysShowLabel = true
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(3)
                                }
                            },
                            icon = {
                                Icon(
                                    Icons.Default.PhotoAlbum,
                                    contentDescription = "Albums"
                                )
                            },
                            label = { Text("Albums", textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = navColors,
                            alwaysShowLabel = true
                        )
                        NavigationBarItem(
                            selected = currentTab == 4,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(4)
                                }
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "Collections"
                                )
                            },
                            label = { Text("Collections", textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = navColors,
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (!hasPermission) {
                // No media access yet: show a clear explainer + action instead of an empty gallery.
                PermissionRequestScreen(
                    onRequestPermission = { permissionLauncher.launch(permissionsToRequest) }
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = pagerScrollEnabled,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> GalleryScreen(galleryViewModel, onAddToCollection = onAddToCollection)
                        1 -> PeopleFoldersScreen(
                            peopleViewModel,
                            allNames,
                            onAddToCollection = onAddToCollection,
                            onRecluster = { galleryService.forceRecluster() }
                        )
                        2 -> CategoryFoldersScreen(categoryViewModel, allNames, onAddToCollection = onAddToCollection)
                        3 -> AlbumsFoldersScreen(albumsViewModel, allNames, onAddToCollection = onAddToCollection)
                        4 -> CollectionsFoldersScreen(collectionsViewModel, allNames, onAddToCollection = onAddToCollection)
                    }
                }
            }
        }
    }

    // Collection picker dialog
    if (showCollectionPicker) {
        CollectionPickerDialog(
            collections = availableCollections,
            favoriteCollectionIds = collectionsViewModel.favoriteFolderIds,
            onDismiss = {
                showCollectionPicker = false
                pendingUrisForCollection = emptySet()
            },
            onSelect = { collection ->
                collectionsViewModel.addMediaToCollection(pendingUrisForCollection, collection.id)
                showCollectionPicker = false
                pendingUrisForCollection = emptySet()
                // Clear selection on the active view model
                when (currentTab) {
                    0 -> galleryViewModel.clearSelection()
                    1 -> peopleViewModel.clearSelection()
                    2 -> categoryViewModel.clearSelection()
                    3 -> albumsViewModel.clearSelection()
                    4 -> collectionsViewModel.clearSelection()
                }
            }
        )
    }

    // First-time tutorial overlay for the current tab (see the LaunchedEffect above).
    // Dismissing marks this tab as seen so it won't show again.
    activeTutorial?.let { tab ->
        TutorialOverlay(
            content = tutorialContentFor(tab),
            onDismiss = {
                tutorialPrefs.markSeen(tab)
                activeTutorial = null
            }
        )
    }
}
