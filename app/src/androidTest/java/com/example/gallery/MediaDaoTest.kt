package com.example.gallery

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.Converters
import com.example.gallery.db.daos.MediaDao
import com.example.gallery.db.entities.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MediaDao].
 *
 * Covers the core database operations used by the background indexing
 * pipeline: insertion, retrieval, deletion, OCR text search, and the
 * ID-set diffing logic that drives incremental syncing with MediaStore.
 *
 * Each test runs against an in-memory Room database so that no persistent
 * state leaks between runs.
 *
 * Run with:
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gallery.MediaDaoTest
 */
@RunWith(AndroidJUnit4::class)
class MediaDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ───────────────── helpers ─────────────────

    private fun makeMedia(id: Long, ocr: String? = null, ts: Long = id) = MediaEntity(
        mediaId    = id,
        timestampMs = ts,
        isVideo    = false,
        embedding  = FloatArray(512) { 0f },
        ocrText    = ocr
    )

    // ───────────────── insertion ─────────────────

    @Test
    fun insertSingle_canBeRetrieved() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L)))
        val all = dao.getAllMedia()
        assertEquals(1, all.size)
        assertEquals(1L, all.first().mediaId)
    }

    @Test
    fun insertMultiple_allPersisted() = runBlocking {
        dao.insertAll((1L..10L).map { makeMedia(it) })
        assertEquals(10, dao.count())
    }

    @Test
    fun insertDuplicate_isIgnored() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L)))
        dao.insertAll(listOf(makeMedia(1L))) // same primary key → IGNORE
        assertEquals(1, dao.count())
    }

    // ───────────────── deletion ─────────────────

    @Test
    fun delete_removesOnlyTargetRow() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L), makeMedia(2L), makeMedia(3L)))
        dao.delete(makeMedia(2L))
        val remaining = dao.getAllMediaIds()
        assertFalse(2L in remaining)
        assertTrue(1L in remaining)
        assertTrue(3L in remaining)
    }

    @Test
    fun deleteAll_clearsTable() = runBlocking {
        dao.insertAll((1L..5L).map { makeMedia(it) })
        dao.deleteAll((1L..5L).map { makeMedia(it) })
        assertEquals(0, dao.count())
    }

    // ───────────────── ID diffing (indexing sync logic) ─────────────────

    @Test
    fun idDiff_detectsNewImages() = runBlocking {
        // Simulate: 3 images on device, 2 already in DB
        dao.insertAll(listOf(makeMedia(10L), makeMedia(20L)))

        val deviceIds = setOf(10L, 20L, 30L)
        val dbIds     = dao.getAllMediaIds().toSet()

        val toAdd = deviceIds - dbIds
        assertEquals(setOf(30L), toAdd)
    }

    @Test
    fun idDiff_detectsDeletedImages() = runBlocking {
        // Simulate: 3 images in DB, one no longer on device
        dao.insertAll(listOf(makeMedia(10L), makeMedia(20L), makeMedia(30L)))

        val deviceIds = setOf(10L, 20L)
        val dbIds     = dao.getAllMediaIds().toSet()

        val toDelete = dbIds - deviceIds
        assertEquals(setOf(30L), toDelete)
    }

    @Test
    fun idDiff_noChanges_bothSetsEmpty() = runBlocking {
        dao.insertAll(listOf(makeMedia(10L), makeMedia(20L)))

        val deviceIds = setOf(10L, 20L)
        val dbIds     = dao.getAllMediaIds().toSet()

        assertTrue((deviceIds - dbIds).isEmpty())
        assertTrue((dbIds - deviceIds).isEmpty())
    }

    // ───────────────── OCR search ─────────────────

    @Test
    fun ocrSearch_findsExactSubstring() = runBlocking {
        dao.insertAll(listOf(
            makeMedia(1L, ocr = "Invoice Total: 350 EGP"),
            makeMedia(2L, ocr = "Happy birthday!"),
            makeMedia(3L, ocr = null)
        ))
        val results = dao.searchMediaSimple("Invoice")
        assertEquals(1, results.size)
        assertEquals(1L, results.first().mediaId)
    }

    @Test
    fun ocrSearch_caseInsensitive() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ocr = "Meeting at 9AM")))
        val lower = dao.searchMediaSimple("meeting")
        val upper = dao.searchMediaSimple("MEETING")
        assertEquals(1, lower.size)
        assertEquals(1, upper.size)
    }

    @Test
    fun ocrSearch_noMatch_returnsEmpty() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ocr = "Some random text")))
        val results = dao.searchMediaSimple("xyz_not_present")
        assertTrue(results.isEmpty())
    }

    @Test
    fun ocrSearch_nullOcr_notReturned() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ocr = null), makeMedia(2L, ocr = "Hello")))
        val results = dao.searchMediaSimple("Hello")
        assertEquals(1, results.size)
        assertEquals(2L, results.first().mediaId)
    }

    // ───────────────── timestamp ordering ─────────────────

    @Test
    fun getAllMedia_orderedByTimestampDescending() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ts = 100L), makeMedia(2L, ts = 300L), makeMedia(3L, ts = 200L)))
        val result = dao.getAllMedia()
        assertEquals(listOf(2L, 3L, 1L), result.map { it.mediaId })
    }

    // ───────────────── getMediaById ─────────────────

    @Test
    fun getMediaById_existingId_returnsEntity() = runBlocking {
        dao.insertAll(listOf(makeMedia(42L, ocr = "test text")))
        val entity = dao.getMediaById(42L)
        assertNotNull(entity)
        assertEquals("test text", entity!!.ocrText)
    }

    @Test
    fun getMediaById_missingId_returnsNull() = runBlocking {
        val entity = dao.getMediaById(999L)
        assertNull(entity)
    }

    // ───────────────── updateOcrText ─────────────────

    @Test
    fun updateOcrText_modifiesOnlyTargetRow() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ocr = "old text"), makeMedia(2L, ocr = "untouched")))
        dao.updateOcrText(1L, "new text")

        assertEquals("new text", dao.getMediaById(1L)!!.ocrText)
        assertEquals("untouched", dao.getMediaById(2L)!!.ocrText)
    }

    @Test
    fun updateOcrText_setOnPreviouslyNullRow() = runBlocking {
        dao.insertAll(listOf(makeMedia(10L, ocr = null)))
        dao.updateOcrText(10L, "freshly set")

        assertEquals("freshly set", dao.getMediaById(10L)!!.ocrText)
    }

    // ───────────────── getMediaByIds ─────────────────

    @Test
    fun getMediaByIds_returnsOnlyRequestedIds() = runBlocking {
        dao.insertAll((1L..5L).map { makeMedia(it) })
        val result = dao.getMediaByIds(listOf(2L, 4L))

        assertEquals(2, result.size)
        val ids = result.map { it.mediaId }.toSet()
        assertTrue(2L in ids)
        assertTrue(4L in ids)
        assertFalse(1L in ids)
    }

    @Test
    fun getMediaByIds_emptyIdList_returnsEmpty() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L), makeMedia(2L)))
        val result = dao.getMediaByIds(emptyList())
        assertTrue(result.isEmpty())
    }

    // ───────────────── searchMediaFts (prefix search) ─────────────────

    @Test
    fun searchMediaFts_emptyQuery_returnsAllMedia() = runBlocking {
        dao.insertAll(listOf(makeMedia(1L, ocr = "Invoice"), makeMedia(2L, ocr = "Birthday")))
        val result = dao.searchMediaFts("")
        assertEquals(2, result.size)
    }

    @Test
    fun searchMediaFts_prefixQuery_findsMatchingRows() = runBlocking {
        dao.insertAll(listOf(
            makeMedia(1L, ocr = "Meeting notes from today"),
            makeMedia(2L, ocr = "Birthday party photos"),
            makeMedia(3L, ocr = null)
        ))
        val result = dao.searchMediaFts("Meet")
        assertEquals(1, result.size)
        assertEquals(1L, result.first().mediaId)
    }
}
