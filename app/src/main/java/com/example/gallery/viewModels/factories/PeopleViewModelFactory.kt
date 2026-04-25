package com.example.gallery.viewModels.factories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.GalleryService
import com.example.gallery.folders.PersonFolderRepository
import com.example.gallery.viewModels.PeopleViewModel

class PeopleViewModelFactory(
    private val personFolderRepository: PersonFolderRepository,
    private val galleryService: GalleryService
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeopleViewModel::class.java)) {
            return PeopleViewModel(personFolderRepository, galleryService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}