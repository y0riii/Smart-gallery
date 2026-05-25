package com.example.gallery.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@Composable
fun ThumbnailImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    sizePx: Int = 384
) {
    val context = LocalContext.current
    var useOriginalRequest by remember(uri) { mutableStateOf(false) }

    val model: Any = remember(context, uri, sizePx, useOriginalRequest) {
        if (useOriginalRequest) {
            uri
        } else {
            ImageRequest.Builder(context)
                .data(uri)
                .size(sizePx)
                .build()
        }
    }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (!useOriginalRequest) {
                useOriginalRequest = true
            }
        }
    )
}
