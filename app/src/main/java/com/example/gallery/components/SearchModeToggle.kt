package com.example.gallery.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    ) {
        Checkbox(
            checked = !useClip,
            onCheckedChange = { isChecked ->
                onChange(!isChecked)
            }
        )
        Text("Document Search (OCR)")
    }
}