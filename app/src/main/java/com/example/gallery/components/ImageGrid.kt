package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@Composable
fun ImageGrid(
    images: List<Uri>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    onImageClick: (Int) -> Unit = {}
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(128.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp)
    ) {
        itemsIndexed(images, key = { index, uri -> "$index-$uri" }) { index, result ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(result)
                    .size(256)
                    .build(),
                contentDescription = "Image result",
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImageClick(index) },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ImageGridPreview() {
    val mockImages = List(10) {
        "android.resource://com.example.gallery/drawable/ic_launcher_background".toUri()
    }
    MaterialTheme {
        Surface {
            ImageGrid(
                images = mockImages,
                gridState = rememberLazyGridState()
            )
        }
    }
}
