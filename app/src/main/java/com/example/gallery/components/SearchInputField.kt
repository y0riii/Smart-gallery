package com.example.gallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.PopupProperties

data class MentionQuery(val start: Int, val end: Int, val query: String)

data class MentionMatch(val start: Int, val end: Int)

fun extractMention(text: String, cursor: Int): MentionQuery? {
    val beforeCursor = text.take(cursor)
    val atIndex = beforeCursor.lastIndexOf('@')

    if (atIndex == -1) return null

    val query = beforeCursor.substring(atIndex + 1)

    return MentionQuery(
        start = atIndex,
        end = cursor,
        query = query
    )
}

fun findMentions(
    text: String,
    allNames: List<String>
): List<MentionMatch> {

    val results = mutableListOf<MentionMatch>()
    val lowerText = text.lowercase()
    val lowerNames = allNames.map { it.lowercase() }

    var i = 0
    while (i < text.length) {
        if (text[i] == '@') {

            var bestEnd = -1

            for (name in lowerNames) {
                val candidate = "@$name"

                if (lowerText.startsWith(candidate, i)) {
                    val end = i + candidate.length

                    // Ensure boundary (space or end of text)
                    val validBoundary =
                        end == text.length || text[end].isWhitespace()

                    if (validBoundary && end > bestEnd) {
                        bestEnd = end
                    }
                }
            }

            if (bestEnd != -1) {
                results.add(MentionMatch(i, bestEnd))
                i = bestEnd
                continue
            }
        }
        i++
    }

    return results
}


fun highlightMentions(text: String, allNames: List<String>) = buildAnnotatedString {

    append(text)

    val matches = findMentions(text, allNames)

    matches.forEach {
        addStyle(
            style = SpanStyle(
                background = Color(0xFF00AAFF),
                color = Color.White
            ),
            start = it.start,
            end = it.end
        )
    }
}

@Composable
fun SearchInputField(
    prompt: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    allNames: List<String>,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit
) {
    var mentionQuery by remember { mutableStateOf<MentionQuery?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    val mentionTransformation = remember(allNames) {
        VisualTransformation { text ->
            val annotated = highlightMentions(text.text, allNames)

            TransformedText(
                annotated,
                OffsetMapping.Identity
            )
        }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = prompt,
            onValueChange = {
                onValueChange(it)
                val mention = extractMention(it.text, it.selection.start)
                mentionQuery = mention
                suggestions = if (mention != null) {
                    allNames.filter { name -> name.startsWith(mention.query, true) }.take(5)
                } else {
                    emptyList()
                }
            },
            placeholder = { Text("Search") },
            singleLine = true,
            visualTransformation = mentionTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = {
                if (mentionQuery == null) {
                    suggestions = emptyList()
                }
            },
            properties = PopupProperties(focusable = false)
        ) {
            suggestions.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        val mq = mentionQuery ?: return@DropdownMenuItem

                        val newText = buildString {
                            append(prompt.text.substring(0, mq.start))
                            append("@${name}")
                            append(" ")
                            append(prompt.text.substring(mq.end))
                        }

                        onValueChange(
                            TextFieldValue(
                                text = newText,
                                selection = TextRange(mq.start + name.length + 2)
                            )
                        )

                        suggestions = emptyList()
                    }
                )
            }
        }
    }
}