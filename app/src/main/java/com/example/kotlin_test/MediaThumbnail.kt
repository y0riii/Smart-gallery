package com.example.kotlin_test

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaThumbnail(uri: Uri, recognizedText: String?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier.width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 90.dp)
                .background(Color.LightGray)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Thumbnail",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        recognizedText?.let { text ->
            Text(
                text = if (text.length > 60) text.take(60) + "…" else text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
