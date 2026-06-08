package com.example.gallery.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
    showDates: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

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
                onFocusChanged = { isFocused = it }
            )
            Spacer(modifier = Modifier.width(8.dp))

            if (prompt.text.isNotEmpty() || fromDate != null || toDate != null) {
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

        if (isFocused || fromDate != null || toDate != null || useClip) {
            if (showDates) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = fromDate?.let { sdf.format(Date(it)) } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("From") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showFromPicker = true }) {
                                    Icon(Icons.Default.DateRange, null)
                                }
                            },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Box(Modifier.matchParentSize().clickable { showFromPicker = true })
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = toDate?.let { sdf.format(Date(it)) } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("To") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showToPicker = true }) {
                                    Icon(Icons.Default.DateRange, null)
                                }
                            },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Box(Modifier.matchParentSize().clickable { showToPicker = true })
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            SearchModeToggle(useClip) {
                onUseClipChange(it)
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
