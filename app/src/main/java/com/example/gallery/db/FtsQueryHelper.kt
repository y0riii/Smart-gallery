package com.example.gallery.db

object FtsQueryHelper {

    fun formatForFts(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ""

        val escapedWords = trimmed.split("\\s+".toRegex())
            .map { it.replace("\"", "\"\"") + "*" } // add * for prefix search

        return escapedWords.joinToString(" OR ")
    }
}