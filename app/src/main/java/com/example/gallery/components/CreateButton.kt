package com.example.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CreateButton(onCreate: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = "Create",
            modifier = Modifier.clickable {
                showDialog = true
            },
            style = MaterialTheme.typography.titleMedium
        )
    }

    if (showDialog) {
        CustomDialog(
            placeholder = "Category name",
            confirmText = "Create",
            onDismiss = { showDialog = false },
            onConfirm = { name ->
                onCreate(name)
                showDialog = false
            }
        )
    }
}