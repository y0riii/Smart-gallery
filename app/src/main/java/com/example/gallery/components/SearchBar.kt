package com.example.gallery.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun SearchBar(isSearching: Boolean, onSearch: (String, Boolean) -> Unit, allNames: List<String>) {

    var prompt by remember { mutableStateOf(TextFieldValue("")) }
    var useClip by remember { mutableStateOf(true) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchInputField(
            prompt = prompt,
            onValueChange = { prompt = it },
            allNames = allNames,
            modifier = Modifier.weight(1f),
            onFocusChanged = { isFocused = it }
        )
        Spacer(modifier = Modifier.width(8.dp))

        Button(
            enabled = !isSearching,
            onClick = { onSearch(prompt.text, useClip) }
        ) {
            Text("Go")
        }
    }

    if (isFocused) {
        SearchModeToggle(useClip) {
            useClip = it
        }
    }
}