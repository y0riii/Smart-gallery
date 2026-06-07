package com.example.gallery

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.components.AlbumsFoldersScreen
import com.example.gallery.components.CategoryFoldersScreen
import com.example.gallery.components.GalleryScreen
import com.example.gallery.components.PeopleFoldersScreen
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.GalleryIndexerWorker
import com.example.gallery.folders.AlbumsFolderRepository
import com.example.gallery.folders.CategoryFolderRepository
import com.example.gallery.folders.PersonFolderRepository
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
        PeopleViewModelFactory(PersonFolderRepository(db.personDao(), galleryService), galleryService)
    }

    private val categoryViewModel: CategoryViewModel by viewModels {
        CategoryViewModelFactory(CategoryFolderRepository(db.categoryDao(), galleryService), galleryService)
    }

    private val albumsViewModel: AlbumsViewModel by viewModels {
        AlbumsViewModelFactory(AlbumsFolderRepository(applicationContext, galleryService), galleryService)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleBackgroundIndexing()
        setContent {
            MaterialTheme {
                GalleryApp(galleryViewModel, peopleViewModel, categoryViewModel, albumsViewModel)
            }
        }
    }

    private fun scheduleBackgroundIndexing() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val indexingRequest = PeriodicWorkRequestBuilder<GalleryIndexerWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "GalleryIndexing",
            ExistingPeriodicWorkPolicy.KEEP,
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

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            galleryViewModel.statusText = "Showing all images."
            galleryViewModel.onPermissionGranted()
        } else {
            galleryViewModel.statusText = "Permission denied."
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

    Scaffold(
        bottomBar = {
            if (!isFullScreen) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Default.People, contentDescription = "People") },
                        label = { Text("People") }
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
                        label = { Text("Tags") }
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
                        label = { Text("Albums") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when {
                !hasPermission -> GalleryScreen(galleryViewModel)

                currentTab == 0 -> GalleryScreen(galleryViewModel)

                currentTab == 1 -> PeopleFoldersScreen(peopleViewModel, allNames)

                currentTab == 2 -> CategoryFoldersScreen(categoryViewModel, allNames)

                currentTab == 3 -> AlbumsFoldersScreen(albumsViewModel, allNames)
            }
        }
    }
}
