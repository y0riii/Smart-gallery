package com.example.gallery.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ImageGridScreen(
    images: List<Uri>,
    onDelete: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    fullScreenIndex: Int?,
    onIndexChanged: (Int?) -> Unit,
    gridState: LazyGridState = rememberLazyGridState()
) {
    Box(modifier = modifier.fillMaxSize()) {

        if (fullScreenIndex == null) {
            ImageGrid(
                images = images,
                gridState = gridState,
                modifier = Modifier.fillMaxSize(),
                onImageClick = { index ->
                    onIndexChanged(index)
                }
            )
        }

        fullScreenIndex?.let { index ->
            FullScreenImage(
                images = images,
                initialIndex = index,
                onClose = { onIndexChanged(null) },
                onDelete = onDelete
            )

            BackHandler {
                onIndexChanged(null)
            }
        }
    }
}
