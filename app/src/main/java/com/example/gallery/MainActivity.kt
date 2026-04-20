package com.example.gallery

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.components.GalleryScreen
import com.example.gallery.components.GalleryViewModel
import com.example.gallery.db.GalleryIndexerWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleBackgroundIndexing()
        setContent {
            MaterialTheme {
                GalleryApp(viewModel)
            }
        }
    }

    private fun scheduleBackgroundIndexing() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true) // Battery > 20%
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
fun GalleryApp(viewModel: GalleryViewModel) {

    var hasPermission by remember { mutableStateOf(false) }

    val permissionsToRequest = remember { getPermissionsToRequest() }

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
            viewModel.statusText = "Showing all images."
            viewModel.onPermissionGranted()
        } else {
            viewModel.statusText = "Permission denied."
        }
    }

    GalleryScreen(viewModel)

}
