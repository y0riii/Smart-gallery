package com.example.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.components.GalleryViewModel
import com.example.gallery.db.AppDatabase

class GalleryViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            val db = AppDatabase.getDatabase(context)
            val service = GalleryService(context)
            return GalleryViewModel(service, db.personDao()) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
