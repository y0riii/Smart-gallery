# Smart Gallery

Smart Gallery is a modern, production-ready Android photo gallery application built with Kotlin and Jetpack Compose. It features a custom design system, fluid animations, and OCR-based image search capabilities.

## Customization Guide

The app's design system is centralized, making it easy to customize its look and feel. Below are instructions on how to modify various aspects of the app.

### 1. Changing the color palette
To change the general color palette, edit the color token values (like `LightBackground`, `DarkSurface`, etc.) in `app/src/main/java/com/example/gallery/ui/theme/Color.kt`. Then, ensure the `lightColorScheme` and `darkColorScheme` definitions in `Theme.kt` map to your new colors.

### 2. Changing the primary accent color
To change the primary blue accent color:
- Update `LightPrimary` and `DarkPrimary` in `Color.kt`.
- Update `AppConfig.MentionHighlight` in `AppConfig.kt` to match your new primary color.

### 3. Changing the font
The app uses the **Inter** font via Google Fonts. To change this:
- Open `app/src/main/java/com/example/gallery/ui/theme/Type.kt`.
- Change `GoogleFont("Inter")` to any other valid Google Fonts name (e.g., `GoogleFont("Roboto")` or `GoogleFont("Outfit")`).
- Adjust the font weights and sizes in the `Typography` object if needed to suit your new font.

### 4. Changing animation durations
All animation timings are defined in `app/src/main/java/com/example/gallery/ui/theme/AppConfig.kt`. Look for properties ending in `Duration` (e.g., `TabTransitionDuration`, `GridResizeDuration`). Adjust the milliseconds to speed up or slow down animations. Set any duration to `0` to disable that specific animation.

### 5. Changing animation easing
To change how animations accelerate and decelerate, edit `AppConfig.StandardEasing` and `AppConfig.EmphasizedEasing` in `AppConfig.kt`. You can use Compose's built-in easings like `LinearOutSlowInEasing`, `FastOutSlowInEasing`, or define custom cubic bezier curves.

### 6. Changing corner radii
To adjust the roundness of UI elements:
- Edit `AppConfig.FolderTileCornerRadius` in `AppConfig.kt` for the folder grid items.
- Edit `AppConfig.SearchFieldCornerRadius` in `AppConfig.kt` for the search bar and date picker inputs.

### 7. Changing spacing and padding
To modify layout spacing:
- Edit `AppConfig.ScreenHorizontalPadding` for the outer margins of the screens.
- Edit `AppConfig.FolderGridSpacing` to change the gap between grid items.
- Edit `AppConfig.FolderGridPadding` for the padding around the grid.
- Edit `AppConfig.FolderTileInnerPadding` for the text padding inside folder cards.

### 8. Disabling dark mode
By default, the app automatically switches between light and dark modes based on system settings. To force the app into light mode always, open `MainActivity.kt` and modify the `setContent` block:
Pass `darkTheme = false` to the `GalleryTheme` wrapper:
```kotlin
GalleryTheme(darkTheme = false) { ... }
```

### 9. Disabling a specific animation
To disable a specific animation without affecting others, open `AppConfig.kt` and set the relevant `Duration` constant to `0`. For example, setting `SearchExpansionDuration = 0` will make the search bar snap open instantly.

### 10. Architecture overview
The app follows a modern Android Architecture (MVVM) entirely written in Kotlin and Jetpack Compose:
- **ViewModels**: Handle the business logic and state management for each screen (`FoldersViewModel`, `CategoryViewModel`, `PeopleViewModel`). They extend a base `DeletableViewModel` to share multi-select and delete logic.
- **Repositories**: Interface with the device's MediaStore or internal database to fetch images, folders, and tags.
- **Compose Components**: The UI is purely declarative, located in the `components/` package. The `MainActivity` serves as the navigation host, switching between composable screens (`GalleryScreen`, `AlbumsFoldersScreen`, `PeopleFoldersScreen`, `CategoryFoldersScreen`).
- **State**: The app relies heavily on Compose State and `Flow` to ensure the UI reactively updates whenever the underlying data or selection state changes.
