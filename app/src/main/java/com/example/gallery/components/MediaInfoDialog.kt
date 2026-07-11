package com.example.gallery.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gallery.GalleryService
import java.util.Locale

/**
 * Single-item Info panel: shows a selected photo/video's name, size, full path, and the user albums
 * it currently belongs to. Shown from a selection bar when exactly one item is selected.
 */
@Composable
fun MediaInfoDialog(info: GalleryService.MediaInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (info.isVideo) "Video info" else "Photo info") },
        text = {
            // Cap height so a long path / many albums scroll instead of pushing the dialog off-screen.
            val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                InfoRow("Name", info.name.ifBlank { "—" })
                InfoRow("Size", formatBytes(info.sizeBytes))
                InfoRow("Path", info.path.ifBlank { "—" })

                Spacer(Modifier.height(12.dp))
                Text("In albums", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (info.albums.isEmpty()) {
                    Text(
                        "Not in any album",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    info.albums.forEach { album ->
                        Text("•  $album", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** One "Label: value" line; the label is fixed-width so values line up. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(56.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Human-readable byte size (B / KB / MB / GB). */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "Unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
