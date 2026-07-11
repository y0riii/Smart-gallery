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
    ALBUMS("albums")
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
            "The first time you open the app, it scans all your photos — and your videos — so it can search them. This can take a while if you have a lot, so just leave it running.",
            "You can follow how it's going on the progress bar at the bottom of the screen and in the notification (it shows photos first, then videos).",
            "Tap Pause or Resume on the notification anytime — processing picks up right where it left off.",
            "Tap the duplicates icon (top-right, next to the gear) to find exact-duplicate photos and videos and gather every copy of each into a \"Detected Duplicates\" album (in the Albums tab) for review — nothing is deleted.",
            "Once processed, type what you're looking for in plain words, like \"sunset at the beach\", and AI finds matching photos.",
            "Flip the toggle to Document search to find text inside pictures — receipts, notes, screenshots.",
            "Turn on \"Search in videos\" to include videos in your searches too (off by default — it searches photos only).",
            "Type \"@\" then a name to find photos of a specific person.",
            "Tap the date field to narrow results to a time range.",
            "Tap the gear icon (top-right) for Settings — switch between Light, Dark, or System theme, toggle automatic face re-grouping, and turn on Arabic text search.",
            "Long-press a photo to select or drag to select several, then share, delete, or add them to an album.",
            "With exactly one photo selected — here or inside any folder — tap the Info icon to see its size, file path, and which albums it's in."
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
            "Long-press people to select them — then tap Rename to rename one, or Merge two or more that are the same person.",
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
            "The app scans your library and automatically adds whatever matches — both photos and videos.",
            "Open an album anytime; newly matching photos and videos are added as they're indexed.",
            "Deleting a photo inside an album lets you choose: \"Remove from album\" takes it out of just this AI album (the file stays on your device), or \"Delete from device\" removes it everywhere."
        )
    )
    TutorialTab.ALBUMS -> TutorialContent(
        icon = Icons.Default.PhotoAlbum,
        title = "Albums",
        summary = "Your device's own folders (Camera, Screenshots, and the rest, including videos), " +
            "plus your own custom albums that you fill with any photos and videos you choose.",
        tips = listOf(
            "Tap any folder or album to browse everything inside it.",
            "Tap Create to make your own album, then long-press photos anywhere and choose \"Add to album\".",
            "Long-press one of your own albums to select it, then tap Rename to rename it or Delete to remove it — your photos are never deleted from the device.",
            "Inside an open folder, deleting a photo gives you a choice: \"Remove from album\" takes it out of just that album but keeps it on your device, while \"Delete from device\" removes it everywhere.",
            "You can only \"Remove from album\" in albums you created — the phone's own folders (Camera, Screenshots, …) only offer \"Delete from device\", since the photo actually lives there.",
            "The \"Detected Duplicates\" album is filled for you when you use Group duplicates on the Home screen — it gathers every copy of each duplicate, side by side, for easy review.",
            "Once a folder is open, you can search or filter within it."
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

    private fun key(tab: TutorialTab) = "seen_${tab.prefsKey}"
}
