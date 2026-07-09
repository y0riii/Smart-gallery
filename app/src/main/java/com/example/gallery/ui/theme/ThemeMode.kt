package com.example.gallery.ui.theme

/**
 * User-selectable app appearance. [SYSTEM] follows the OS light/dark setting and is the default on
 * a fresh install; picking [LIGHT] or [DARK] pins the app to that scheme and is persisted.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}
