package com.example.gallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary            = LightPrimary,
    onPrimary          = LightOnPrimary,
    background         = LightBackground,
    onBackground       = LightOnBackground,
    surface            = LightSurface,
    onSurface          = LightOnSurface,
    onSurfaceVariant   = LightOnSurfaceVar,
    surfaceVariant     = LightSurfaceVar,
    outline            = LightOutline,
    outlineVariant     = LightOutlineVar,
    // Keep error/tertiary at Material defaults so AlertDialogs look standard
)

private val DarkColorScheme = darkColorScheme(
    primary            = DarkPrimary,
    onPrimary          = DarkOnPrimary,
    background         = DarkBackground,
    onBackground       = DarkOnBackground,
    surface            = DarkSurface,
    onSurface          = DarkOnSurface,
    onSurfaceVariant   = DarkOnSurfaceVar,
    surfaceVariant     = DarkSurfaceVar,
    outline            = DarkOutline,
    outlineVariant     = DarkOutlineVar,
)

/**
 * GalleryTheme — wraps the entire app.
 *
 * HOW TO CUSTOMIZE:
 *  • Dark mode  → pass darkTheme = false to always use light, or true for always dark
 *  • Palette    → edit token values in Color.kt then reference them in the schemes above
 */
@Composable
fun GalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // dynamicColor is intentionally OFF so our custom palette is always applied.
    // To re-enable Android 12+ wallpaper-based dynamic color, restore the
    // dynamicDarkColorScheme / dynamicLightColorScheme branch and set dynamicColor = true.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}