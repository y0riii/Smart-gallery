package com.example.gallery.components.tutorial

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five navbar tabs, in the same order as the pager pages in MainActivity (0..4).
 * [prefsKey] is the stable id used to remember whether the user has already seen this tab's
 * first-time tutorial. Enum order MUST match the pager page indices.
 */
enum class TutorialTab(val prefsKey: String) {
    HOME("home"),
    PEOPLE("people"),
    AI_ALBUMS("ai_albums"),
    ALBUMS("albums"),
    COLLECTIONS("collections")
}

/**
 * Content shown in a tab's first-time tutorial overlay.
 *
 * @param icon    the tab's navbar icon, repeated in the overlay so the user connects the two
 * @param title   short headline ("Search your photos")
 * @param summary one or two sentences on WHAT this tab is for
 * @param tips     bite-sized "HOW to use it" steps, shown as a checklist
 */
data class TutorialContent(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val tips: List<String>
)

/** Returns the tutorial copy for [tab]. Kept here so all first-run wording lives in one place. */
fun tutorialContentFor(tab: TutorialTab): TutorialContent = when (tab) {
    TutorialTab.HOME -> TutorialContent(
        icon = Icons.Default.Home,
        title = "Welcome to Smart Gallery",
        summary = "Before search and people can work, the app first needs to process your photos " +
            "so it can understand what's in them. This happens in the background — you can keep " +
            "using the app while it runs.",
        tips = listOf(
            "Please wait while your photos are processed — the first run can take a while on a large library.",
            "Watch the progress in the notification and in the progress bar at the bottom of the screen.",
            "Tap Pause or Resume on the notification anytime — processing picks up right where it left off.",
            "Once processed, type what you're looking for in plain words, like \"sunset at the beach\", and AI finds matching photos.",
            "Flip the toggle to Document search to find text inside pictures — receipts, notes, screenshots.",
            "Type \"@\" then a name to find photos of a specific person.",
            "Tap the date field to narrow results to a time range.",
            "Tap the gear icon (top-right) for Settings — switch between Light, Dark, or System theme, toggle automatic face re-grouping, and turn on Arabic text search.",
            "Long-press a photo to select or drag to select several, then share, delete, or add them to a collection."
        )
    )
    TutorialTab.PEOPLE -> TutorialContent(
        icon = Icons.Default.People,
        title = "People",
        summary = "Smart Gallery automatically finds faces in your photos and groups them into " +
            "people — all on your device.",
        tips = listOf(
            "Tap a person to see every photo they appear in.",
            "Tap the name to rename them, e.g. from \"#p1\" to \"Mom\".",
            "Select two or more groups and tap Merge if the same person was split up.",
            "People keep appearing as your photos finish processing — it can take a while on first launch."
        )
    )
    TutorialTab.AI_ALBUMS -> TutorialContent(
        icon = Icons.Default.CollectionsBookmark,
        title = "AI Albums",
        summary = "Albums that fill themselves. Describe a theme and the app finds every matching " +
            "photo for you — no manual sorting.",
        tips = listOf(
            "Tap Create and type a theme like \"food\", \"cars\", or \"mountains\".",
            "The app scans your library and automatically adds the photos that match.",
            "Open an album anytime; newly matching photos are added as they're indexed."
        )
    )
    TutorialTab.ALBUMS -> TutorialContent(
        icon = Icons.Default.PhotoAlbum,
        title = "Albums",
        summary = "Your device's own folders — Camera, Screenshots, Downloads, and the rest — " +
            "including videos.",
        tips = listOf(
            "Tap a folder to browse everything inside it.",
            "Once a folder is open, you can search or filter within it.",
            "These come straight from your phone's storage, so they mirror your other gallery apps."
        )
    )
    TutorialTab.COLLECTIONS -> TutorialContent(
        icon = Icons.Default.Folder,
        title = "Collections",
        summary = "Your own hand-picked groups of photos, organized exactly how you want.",
        tips = listOf(
            "Tap Create to start a new collection.",
            "From any screen, long-press photos and choose \"Add to collection\".",
            "Tap the star on a collection to pin it to the top."
        )
    )
}

/**
 * Remembers, per tab, whether the first-time tutorial has already been shown, so each overlay
 * appears exactly once. Backed by a dedicated SharedPreferences file.
 */
class TutorialPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("tutorial_prefs", Context.MODE_PRIVATE)

    fun hasSeen(tab: TutorialTab): Boolean = prefs.getBoolean(key(tab), false)

    fun markSeen(tab: TutorialTab) {
        prefs.edit().putBoolean(key(tab), true).apply()
    }

    /** Clears all "seen" flags so every tutorial shows again — handy for QA / a future "replay" option. */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    private fun key(tab: TutorialTab) = "seen_${tab.prefsKey}"
}
