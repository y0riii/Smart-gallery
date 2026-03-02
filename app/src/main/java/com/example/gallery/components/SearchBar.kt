package com.example.gallery.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SearchBar(isSearching: Boolean, onSearch: (String, Boolean) -> Unit) {

    var prompt by remember { mutableStateOf("") }
    var useClip by remember { mutableStateOf(true) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Search") },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        Spacer(modifier = Modifier.width(8.dp))

        Button(
            enabled = !isSearching,
            onClick = { onSearch(prompt, useClip) }
        ) {
            Text("Go")
        }
    }

    SearchModeToggle(useClip) {
        useClip = it
    }
}