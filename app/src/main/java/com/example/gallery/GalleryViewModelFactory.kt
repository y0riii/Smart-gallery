package com.example.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.components.GalleryViewModel
import com.example.gallery.db.AppDatabase
import com.example.gallery.folders.PersonFolderRepository
import com.example.gallery.folders.FoldersViewModel

class GalleryViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getDatabase(context)
        val service = GalleryService(context)
        
        return when {
            modelClass.isAssignableFrom(GalleryViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                GalleryViewModel(service, db.personDao()) as T
            }
            modelClass.isAssignableFrom(FoldersViewModel::class.java) -> {
                val repository = PersonFolderRepository(db.personDao())
                @Suppress("UNCHECKED_CAST")
                FoldersViewModel(repository, service) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
