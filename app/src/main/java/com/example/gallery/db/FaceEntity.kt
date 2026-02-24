package com.example.gallery.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "face_recognition")
data class FaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String? = null,
    val embedding: FloatArray
)