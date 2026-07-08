package com.example.gallery.db.daos

/**
 * Stitches together the "two flat queries + in-memory group" pattern shared by the
 * Category / Collection / Person DAOs.
 *
 * Each of those DAOs fetches a list of lightweight "preview" rows and a separate flat list of
 * cross-ref rows (owner id ↔ media id), then groups the refs by owner to attach each preview's
 * media-id list. This centralises that stitch so the logic lives in one place.
 *
 * @param previews    the preview rows (one per folder/person/category/collection)
 * @param refs        the flat cross-ref rows linking an owner to a media id
 * @param previewId   extracts a preview's id
 * @param refOwnerId  extracts the owner id a cross-ref belongs to
 * @param refMediaId  extracts the media id a cross-ref points to
 */
fun <P, R> associateMediaIds(
    previews: List<P>,
    refs: List<R>,
    previewId: (P) -> Long,
    refOwnerId: (R) -> Long,
    refMediaId: (R) -> Long
): List<Pair<P, List<Long>>> {
    val mediaMap = refs.groupBy(keySelector = refOwnerId, valueTransform = refMediaId)
    return previews.map { preview -> preview to mediaMap[previewId(preview)].orEmpty() }
}
