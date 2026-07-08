package com.example.gallery.folders

/**
 * Single source of truth for how people are ordered in the People UI.
 *
 * The rule (previously duplicated in PersonFolderRepository, PeopleViewModel, and mirrored in
 * PersonDao's SQL) is:
 *   1. Favorites first — only when a non-empty favoriteIds set is passed.
 *   2. Named people next, A–Z case-insensitively.
 *   3. Auto-generated placeholders (#p1, #p2, …) last, ordered numerically so #p2 precedes #p10.
 *
 * Pass an empty favoriteIds set to skip the favorites tier (e.g. the repository, which sorts
 * before favorites are known).
 */
object PersonSort {
    private val PLACEHOLDER_REGEX = Regex("^#p\\d+$", RegexOption.IGNORE_CASE)

    private const val GROUP_FAVORITE = 0
    private const val GROUP_NAMED = 1
    private const val GROUP_PLACEHOLDER = 2

    /** True if [name] is a system-generated placeholder like "#p1". */
    fun isPlaceholder(name: String) = PLACEHOLDER_REGEX.matches(name)

    /** Numeric suffix of a "#p<n>" placeholder, or MAX_VALUE if it can't be parsed. */
    private fun placeholderNumber(name: String) =
        name.removePrefix("#p").removePrefix("#P").toIntOrNull() ?: Int.MAX_VALUE

    private fun groupOf(item: FolderItem, favoriteIds: Set<Long>): Int = when {
        favoriteIds.contains(item.bucketId) -> GROUP_FAVORITE
        !isPlaceholder(item.name) -> GROUP_NAMED
        else -> GROUP_PLACEHOLDER
    }

    fun comparator(favoriteIds: Set<Long>): Comparator<FolderItem> = Comparator { a, b ->
        val aGroup = groupOf(a, favoriteIds)
        val bGroup = groupOf(b, favoriteIds)
        when {
            aGroup != bGroup -> aGroup.compareTo(bGroup)
            // Within the placeholder tier, order by the numeric suffix (#p2 before #p10).
            aGroup == GROUP_PLACEHOLDER ->
                placeholderNumber(a.name).compareTo(placeholderNumber(b.name))
            // Favorites and named tiers order alphabetically, case-insensitively.
            else -> a.name.lowercase().compareTo(b.name.lowercase())
        }
    }
}
