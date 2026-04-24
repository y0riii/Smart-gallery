package com.example.gallery.viewModels.factories

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderSource
import com.example.gallery.viewModels.FoldersViewModel

class FoldersViewModelFactory(
    private val folderSource: FolderSource,
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FoldersViewModel::class.java)) {
            val service = GalleryService(context)
            return FoldersViewModel(folderSource, service) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}