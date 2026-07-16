package com.example.gallery.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.gallery.ui.theme.AppConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    prompt: TextFieldValue,
    onPromptChange: (TextFieldValue) -> Unit,
    useClip: Boolean,
    onUseClipChange: (Boolean) -> Unit,
    fromDate: Long?,
    onFromDateChange: (Long?) -> Unit,
    toDate: Long?,
    onToDateChange: (Long?) -> Unit,
    isSearching: Boolean,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    allNames: List<String>,
    // True while a search/filter is applied but not yet cleared — keeps the Clear button available
    // even if the user has emptied the text field.
    searchActive: Boolean = false,
    showDates: Boolean = true,
    searchVideos: Boolean = false,
    onSearchVideosChange: (Boolean) -> Unit = {},
    showVideoToggle: Boolean = false,
    // Feature 5: optional map of person name → thumbnail URI, forwarded to SearchInputField
    // for showing circular avatars in the @mention dropdown. Defaults to empty (no avatars).
    nameThumbnails: Map<String, android.net.Uri?> = emptyMap()
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                onPromptChange(TextFieldValue(text = spokenText, selection = TextRange(spokenText.length)))
            }
        }
    }

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchInputField(
                prompt = prompt,
                onValueChange = onPromptChange,
                allNames = allNames,
                modifier = Modifier.weight(1f),
                onFocusChanged = { isFocused = it },
                // Feature 5: pass through so dropdown can show person avatars
                nameThumbnails = nameThumbnails,
                onVoiceClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak search terms...")
                    }
                    voiceLauncher.launch(intent)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))

            if (!isSearching && (prompt.text.isNotEmpty() || fromDate != null || toDate != null || searchActive)) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            Button(
                enabled = !isSearching,
                onClick = {
                    focusManager.clearFocus()
                    onSearch()
                }
            ) {
                Text("Go")
            }
        }

        AnimatedVisibility(
            visible = isFocused || fromDate != null || toDate != null || !useClip,
            enter = expandVertically(tween(AppConfig.SearchExpansionDuration)) + fadeIn(
                tween(
                    AppConfig.SearchExpansionDuration
                )
            ),
            exit = shrinkVertically(tween(AppConfig.SearchExpansionDuration)) + fadeOut(
                tween(
                    AppConfig.SearchExpansionDuration
                )
            )
        ) {
            Column {
                if (showDates) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TextField(
                                value = fromDate?.let { sdf.format(Date(it)) } ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("From") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { showFromPicker = true }) {
                                        Icon(Icons.Default.DateRange, contentDescription = "Pick start date")
                                    }
                                },
                                shape = RoundedCornerShape(AppConfig.SearchFieldCornerRadius),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Box(Modifier
                                .matchParentSize()
                                .clickable { showFromPicker = true })
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            TextField(
                                value = toDate?.let { sdf.format(Date(it)) } ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("To") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { showToPicker = true }) {
                                        Icon(Icons.Default.DateRange, contentDescription = "Pick end date")
                                    }
                                },
                                shape = RoundedCornerShape(AppConfig.SearchFieldCornerRadius),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Box(Modifier
                                .matchParentSize()
                                .clickable { showToPicker = true })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                SearchModeToggle(useClip) {
                    onUseClipChange(it)
                }
                if (showVideoToggle) {
                    VideoSearchToggle(searchVideos, onSearchVideosChange)
                }
            }
        }
    }

    if (showFromPicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = fromDate)
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onFromDateChange(datePickerState.selectedDateMillis)
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onFromDateChange(null)
                    showFromPicker = false
                }) { Text("Clear") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showToPicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = toDate)
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onToDateChange(datePickerState.selectedDateMillis)
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onToDateChange(null)
                    showToPicker = false
                }) { Text("Clear") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
