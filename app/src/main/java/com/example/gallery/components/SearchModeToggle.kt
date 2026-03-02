package com.example.gallery.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SearchModeToggle(
    useClip: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp)) {
        Text("Search Type:", style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = useClip,
                onClick = { onChange(true) }
            )
            Text("Image Search (CLIP)")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = !useClip,
                onClick = { onChange(false) }
            )
            Text("Document Search (OCR)")
        }
    }
}