package com.example.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.components.AlbumsFoldersScreen
import com.example.gallery.components.CategoryFoldersScreen
import com.example.gallery.components.GalleryScreen
import com.example.gallery.components.PeopleFoldersScreen
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.GalleryPeriodicTriggerWorker
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleBackgroundIndexing()
        setContent {
            GalleryTheme {
                GalleryApp(galleryViewModel, peopleViewModel, categoryViewModel, albumsViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasAnyPermission(this)) {
            galleryService.startIndexingWorkManager()
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
            PeriodicWorkRequestBuilder<GalleryPeriodicTriggerWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "GalleryIndexing",
            ExistingPeriodicWorkPolicy.UPDATE,
            indexingRequest
        )
    }
}

private fun getPermissionsToRequest(): Array<String> {
    return when {
        // Android 14+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        }
        // Android 13
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
        // Android 12 and below
        else -> {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

@Composable
fun GalleryApp(
    galleryViewModel: GalleryViewModel,
    peopleViewModel: PeopleViewModel,
    categoryViewModel: CategoryViewModel,
    albumsViewModel: AlbumsViewModel
) {

    var hasPermission by remember { mutableStateOf(false) }
    var currentTab by remember { mutableIntStateOf(0) }

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
        }
    }

    val isFullScreen = when {
        !hasPermission -> galleryViewModel.fullScreenIndex != null
        currentTab == 0 -> galleryViewModel.fullScreenIndex != null
        currentTab == 1 -> peopleViewModel.fullScreenIndex != null
        currentTab == 2 -> categoryViewModel.fullScreenIndex != null
        currentTab == 3 -> albumsViewModel.fullScreenIndex != null
        else -> false
    }

    val isSelecting = when {
        !hasPermission -> galleryViewModel.isSelecting
        currentTab == 0 -> galleryViewModel.isSelecting
        currentTab == 1 -> peopleViewModel.isSelecting
        currentTab == 2 -> categoryViewModel.isSelecting
        currentTab == 3 -> albumsViewModel.isSelecting
        else -> false
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        bottomBar = {
            if (!isFullScreen && !isSelecting) {
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
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = { Icon(Icons.Default.People, contentDescription = "People") },
                            label = { Text("People") },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = {
                                Icon(
                                    Icons.Default.CollectionsBookmark,
                                    contentDescription = "Tags"
                                )
                            },
                            label = { Text("Tags") },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = {
                                Icon(
                                    Icons.Default.PhotoAlbum,
                                    contentDescription = "Albums"
                                )
                            },
                            label = { Text("Albums") },
                            colors = navColors
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (!hasPermission) {
                GalleryScreen(galleryViewModel)
            } else {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        fadeIn(
                            tween(
                                AppConfig.TabTransitionDuration,
                                easing = AppConfig.StandardEasing
                            )
                        ) togetherWith
                                fadeOut(
                                    tween(
                                        AppConfig.TabTransitionDuration,
                                        easing = AppConfig.StandardEasing
                                    )
                                )
                    },
                    label = "TabTransition"
                ) { tab ->
                    when (tab) {
                        0 -> GalleryScreen(galleryViewModel)
                        1 -> PeopleFoldersScreen(peopleViewModel, allNames)
                        2 -> CategoryFoldersScreen(categoryViewModel, allNames)
                        3 -> AlbumsFoldersScreen(albumsViewModel, allNames)
                    }
                }
            }
        }
    }
}
