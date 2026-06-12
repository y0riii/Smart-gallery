package com.example.gallery.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppConfig {
    // Shape
    val FolderTileCornerRadius = 16.dp
    val SearchFieldCornerRadius = 12.dp
    val FolderThumbnailSize = 48.dp
    val SelectionIconSize = 22.dp
    // Feature 1: size of the person thumbnail shown in the folder tile caption row
    val FolderPersonThumbSize = 40.dp
    // Feature 1: corner radius for the square-with-rounded-corners person thumbnail in the tile caption
    val FolderPersonThumbCornerRadius = 6.dp
    // Feature 5: size of the circular person avatar in the @mention dropdown
    val MentionAvatarSize = 32.dp
    // Feature 5: corner radius for the person avatar in the @mention dropdown
    val MentionAvatarCornerRadius = 6.dp
    // Avatar Outline
    val AvatarOutlineWidth = 1.5.dp

    // Spacing
    val ScreenHorizontalPadding = 16.dp
    val FolderGridSpacing = 12.dp
    val FolderGridPadding = 12.dp
    val FolderTileInnerPadding = 12.dp
    // Feature 4: max height of the scrollable @mention suggestions dropdown
    val MentionDropdownMaxHeight = 240.dp

    // Animation durations
    const val TabTransitionDuration = 300
    const val ScreenEnterDuration = 220
    const val ScreenExitDuration = 180
    const val GridResizeDuration = 200
    const val SelectionBarDuration = 250
    const val SearchExpansionDuration = 200
    const val StatusTextDuration = 200

    // Easing
    val StandardEasing = FastOutSlowInEasing
    val EmphasizedEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // Elevation
    val FolderTileElevation = 2.dp
    val FolderTileElevationPressed = 4.dp

    // Color
    val MentionHighlight = Color(0xFF007AFF)
}
