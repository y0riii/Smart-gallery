package com.example.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

/**
 * Application entry point. Its main job is to configure Coil's singleton [ImageLoader] so grid and
 * folder thumbnails scroll smoothly: a large in-memory bitmap cache means recently-seen thumbnails
 * are reused instantly instead of being re-decoded from disk on every scroll. (Coil keeps its
 * default on-disk cache too, so decoded thumbnails also survive across app launches.)
 */
class SmartGalleryApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Use a generous slice of app memory for the thumbnail cache (default is ~0.2).
                    .maxSizePercent(context, 0.30)
                    .build()
            }
            .build()
    }
}
