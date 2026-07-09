package com.example.gallery.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.example.gallery.ui.theme.ThemeMode

/**
 * App-settings dialog. Exposes appearance (light/dark/system), the periodic face re-cluster toggle,
 * and the opt-in Arabic OCR toggle. All choices are persisted by the caller (GalleryViewModel).
 */
@Composable
fun SettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    autoReclusterEnabled: Boolean,
    onAutoReclusterChange: (Boolean) -> Unit,
    arabicOcrEnabled: Boolean,
    onArabicOcrChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            // Cap the (scrollable) content at 80% of screen height so a tall settings list never
            // pushes the dialog past the screen; beyond that it scrolls instead of growing.
            val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
            Column(
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Appearance ────────────────────────────────────────────────
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeModeChange(mode) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ── Auto re-cluster faces ─────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto re-group faces", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Periodically re-cluster all faces (after every 300 new ones) to " +
                                "correct grouping drift. Named people and favorites are preserved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = autoReclusterEnabled, onCheckedChange = onAutoReclusterChange)
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ── Arabic OCR ────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Arabic text search", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Also scan photos for Arabic text. Indexing is slower, and it " +
                                "works best on clear printed text. Requires the Arabic model to be " +
                                "bundled in the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = arabicOcrEnabled, onCheckedChange = onArabicOcrChange)
                }
                if (arabicOcrEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Re-scanning your photos for text — watch the progress bar at the bottom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
