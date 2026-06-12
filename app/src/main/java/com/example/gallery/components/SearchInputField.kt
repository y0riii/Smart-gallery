package com.example.gallery.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.example.gallery.ui.theme.AppConfig

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
                background = AppConfig.MentionHighlight,
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
    onFocusChanged: (Boolean) -> Unit,
    // Feature 5: optional map of name → thumbnail URI; when provided, shows a circular
    // avatar to the left of each name in the @mention dropdown.
    nameThumbnails: Map<String, Uri?> = emptyMap()
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
        TextField(
            value = prompt,
            onValueChange = {
                onValueChange(it)
                val mention = extractMention(it.text, it.selection.start)
                mentionQuery = mention
                suggestions = if (mention != null) {
                    // Feature 4: allow up to 20 results now that the dropdown is scrollable
                    allNames.filter { name -> name.startsWith(mention.query, true) }.take(20)
                } else {
                    emptyList()
                }
            },
            placeholder = { Text("Search") },
            singleLine = true,
            visualTransformation = mentionTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) },
            shape = RoundedCornerShape(AppConfig.SearchFieldCornerRadius),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = {
                if (mentionQuery == null) {
                    suggestions = emptyList()
                }
            },
            properties = PopupProperties(focusable = false),
            // Feature 4: cap the dropdown height so it becomes scrollable when there are many results
            modifier = Modifier.heightIn(max = AppConfig.MentionDropdownMaxHeight)
        ) {
            suggestions.forEach { name ->
                // Feature 5: compute the leading icon BEFORE the DropdownMenuItem call.
                // We use an explicit (@Composable () -> Unit)? type so Kotlin correctly
                // treats the lambda as @Composable. The ?.let { } pattern cannot propagate
                // @Composable through the lambda chain and silently produces null.
                val thumbUri = nameThumbnails[name]
                val personAvatar: (@Composable () -> Unit)? = if (thumbUri != null) {
                    {
                        AsyncImage(
                            model = thumbUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(AppConfig.MentionAvatarSize)  // size from AppConfig
                                .clip(RoundedCornerShape(AppConfig.MentionAvatarCornerRadius)) // square with rounded corners
                                .border(
                                    BorderStroke(
                                        AppConfig.AvatarOutlineWidth,
                                        MaterialTheme.colorScheme.surface
                                    ),
                                    RoundedCornerShape(AppConfig.MentionAvatarCornerRadius)
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else null

                DropdownMenuItem(
                    leadingIcon = personAvatar,   // null → no icon and no reserved space
                    text = { Text(name) },
                    modifier = Modifier.fillMaxWidth(),  // stretch item to full dropdown width
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