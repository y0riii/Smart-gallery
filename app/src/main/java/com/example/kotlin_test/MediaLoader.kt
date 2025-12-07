package com.example.kotlin_test

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

class MediaLoader(private val database: AppDatabase) {

    // Now returns entities from our DB
    suspend fun loadMedia(context: Context): List<MediaEntity> {
        val systemList = mutableListOf<MediaEntity>()

        // 1. Load from System (MediaStore)
        loadImagesFromSystem(context, systemList)
        loadVideosFromSystem(context, systemList)

        // 2. Save to SQLite (Room)
        // We use IGNORE strategy in DAO, so if we already OCR'd an image,
        // we won't overwrite it with a blank one.
        database.mediaDao().insertAll(systemList)

        // 3. Return what is inside our Database
        return database.mediaDao().getAllMedia()
    }

    private fun loadImagesFromSystem(context: Context, list: MutableList<MediaEntity>) {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sort
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val date = cursor.getLong(dateIdx) * 1000L
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                // Convert to Entity immediately
                list.add(MediaEntity(uri.toString(), false, date, null))
            }
        }
    }

    private fun loadVideosFromSystem(context: Context, list: MutableList<MediaEntity>) {
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sort
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val date = cursor.getLong(dateIdx) * 1000L
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                list.add(MediaEntity(uri.toString(), true, date, null))
            }
        }
    }
}