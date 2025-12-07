package com.example.kotlin_test

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Runtime flag for FTS support
    private var ftsSupported: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Database
        val db = AppDatabase.getDatabase(applicationContext)

        // ------------------------------------------------------------------
        // DETECT IF FTS TABLE EXISTS (ROOM cannot verify FTS automatically)
        // ------------------------------------------------------------------

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.openHelper.readableDatabase
                    .query("SELECT rowid FROM media_items_fts LIMIT 1")
                    .use { cursor -> ftsSupported = true }
            } catch (e: Exception) {
                ftsSupported = false
            }
        }

        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }

        setContent {
            MaterialTheme {

                var mediaList by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
                var scanning by remember { mutableStateOf(false) }

                var query by remember { mutableStateOf("") }
                var searchResults by remember { mutableStateOf<List<MediaEntity>?>(null) }

                val scope = rememberCoroutineScope()
                val ctx = LocalContext.current

                Scaffold(
                    topBar = {}
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).padding(12.dp)) {

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search text") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row {

                            // LOAD BUTTON
                            Button(onClick = {
                                val permission =
                                    if (Build.VERSION.SDK_INT >= 33)
                                        Manifest.permission.READ_MEDIA_IMAGES
                                    else Manifest.permission.READ_EXTERNAL_STORAGE

                                if (ContextCompat.checkSelfPermission(
                                        ctx,
                                        permission
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    scope.launch {
                                        val loader = MediaLoader(db)
                                        mediaList = withContext(Dispatchers.IO) {
                                            loader.loadMedia(ctx)
                                        }
                                        searchResults = null
                                        Toast.makeText(
                                            ctx,
                                            "${mediaList.size} items loaded",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    permissionLauncher.launch(permission)
                                }
                            }) {
                                Text("Load")
                            }

                            // OCR BUTTON
                            Button(onClick = {
                                if (mediaList.isEmpty()) return@Button
                                scope.launch {
                                    scanning = true
                                    val processor = OcrProcessor(db)

                                    processor.runOcrOnImages(ctx, mediaList)

                                    mediaList = withContext(Dispatchers.IO) {
                                        db.mediaDao().getAllMedia()
                                    }

                                    scanning = false
                                    Toast.makeText(ctx, "OCR Saved", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Scan OCR")
                            }

                            // SEARCH BUTTON
                            Button(onClick = {
                                val q = query.trim()

                                scope.launch {
                                    searchResults = if (q.isEmpty()) {
                                        null
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            if (ftsSupported) {
                                                // Convert MediaWithRank to MediaEntity
                                                db.mediaDao().searchMediaFts(q)
                                            } else {
                                                db.mediaDao().searchMediaSimple(q)
                                            }
                                        }
                                    }
                                }
                            }) {
                                Text("Search DB")
                            }
                        }

                        val shownList = searchResults ?: mediaList

                        LazyVerticalGrid(columns = GridCells.Fixed(3)) {
                            items(shownList) { item ->
                                MediaThumbnail(
                                    uri = item.uriString.toUri(),
                                    recognizedText = item.ocrText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
