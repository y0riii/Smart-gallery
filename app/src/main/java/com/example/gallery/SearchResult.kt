package com.example.gallery

import android.net.Uri

/**
 * The outcome of a search.
 *
 * @param uris           the matching media, already in display order
 * @param relevantCount  for a SCORED (semantic) search: how many of the LEADING [uris] are "strong"
 *                       matches — i.e. their similarity cleared the relevance threshold. Since
 *                       results are sorted best-first these are the first [relevantCount] items, and
 *                       the UI draws a "less relevant" separator at that boundary and reports this
 *                       as the result count. `null` means the search had no similarity score at all
 *                       (date-only, OCR, name-only, date-sorted) — no separator, count = uris.size.
 */
data class SearchResult(val uris: List<Uri>, val relevantCount: Int?) {
    companion object {
        /** A result with no relevance scoring — no separator; the whole list is "the results". */
        fun all(uris: List<Uri>) = SearchResult(uris, null)
    }
}
