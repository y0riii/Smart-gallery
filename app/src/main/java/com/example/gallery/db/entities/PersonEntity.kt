package com.example.gallery.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String? = null,
    val thumbnailPath: String,
    val thumbnailSize: Int,
    val Embedding: FloatArray,
    val count: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}