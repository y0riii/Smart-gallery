package com.example.gallery

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GalleryApp()
            }
        }
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
fun GalleryApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { GalleryService(context) }

    // --- State Variables ---
    var prompt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Request permissions to load images.") }

    // This holds the final sorted list of images to display
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var useClip by remember { mutableStateOf(true) }

    // --- Permission Handling (NEW) ---
    val permissionsToRequest = remember { getPermissionsToRequest() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            scope.launch {
                isLoading = true
                images = service.loadAllIndexedImages()
                isLoading = false
            }
        } else {
            statusText = "Permission denied."
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    // --- UI Layout ---
    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(16.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            OutlinedTextField(
//                value = prompt,
//                onValueChange = { prompt = it },
//                label = { Text("Search prompt") },
//                modifier = Modifier.weight(1f),
//                singleLine = true
//            )
//            Spacer(modifier = Modifier.width(8.dp))
//            Button(
//                onClick = {
//                    scope.launch {
//                        isLoading = true
//                        images = service.search(prompt)
//                        isLoading = false
//                    }
//                },
//                enabled = !isLoading
//            ) {
//                Text("Go")
//            }
//        }


        // --------------------
        // SEARCH BAR
        // --------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Search") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))

            Button(
                enabled = !isLoading,
                onClick = {
                    scope.launch {
                        isLoading = true
                        statusText = "Searching..."

                        images = if (useClip) {
                            service.search(prompt)            // IMAGE SEARCH USING CLIP
                        } else {
                            service.searchDocuments(prompt)  // DOCUMENT TEXT SEARCH USING OCR
                        }

                        statusText = if (useClip)
                            "Showing CLIP image search results"
                        else
                            "Showing OCR document search results"

                        isLoading = false
                    }
                }
            ) {
                Text("Go")
            }
        }

        // --------------------
        // SEARCH MODE TOGGLE (CLIP / OCR)
        // --------------------
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text("Search Type:", style = MaterialTheme.typography.bodyMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = useClip,
                    onClick = { useClip = true }
                )
                Text("Image Search (CLIP)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !useClip,
                    onClick = { useClip = false }
                )
                Text("Document Search (OCR)")
            }
        }

        // Status/Loading
        if (isLoading || statusText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Image Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(images, key = { it.toString() }) { result ->
                AsyncImage(
                    model = result,
                    contentDescription = "Image result",
                    modifier = Modifier
                        .padding(4.dp)
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}