package com.example.gallery

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.Converters
import com.example.gallery.db.entities.FaceEntity
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.ml.face.ChineseWhispers
import com.example.gallery.utils.VectorUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * Instrumented tests covering the face recognition pipeline and clustering logic.
 *
 * Tests are divided into two areas:
 *
 * 1. **Database layer** — verifies that faces are correctly persisted, linked
 *    to media items, cascade-deleted when the parent image is removed, and
 *    properly assigned to person clusters.
 *
 * 2. **Clustering algorithm** — validates that [ChineseWhispers] groups
 *    well-separated synthetic embeddings correctly, handles edge cases,
 *    and that every input index appears in exactly one output cluster.
 *
 * Run with:
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gallery.FaceAndClusteringTest
 */
@RunWith(AndroidJUnit4::class)
class FaceAndClusteringTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    // ───────────────── helpers ─────────────────

    private fun makeMedia(id: Long) = MediaEntity(
        mediaId     = id,
        timestampMs = id,
        isVideo     = false,
        embedding   = FloatArray(512) { 0f }
    )

    private fun makeFace(mediaId: Long, personId: Long? = null, seed: Int = 0) = FaceEntity(
        mediaId   = mediaId,
        embedding = FloatArray(128) { (seed + it).toFloat() / 128f },
        boxLeft   = 0, boxTop = 0, boxRight = 100, boxBottom = 100,
        personId  = personId
    )

    private fun makePerson(id: Long, name: String = "#p$id") = PersonEntity(
        id            = id,
        name          = name,
        thumbnailPath = "",
        thumbnailSize = 0,
        Embedding     = FloatArray(128) { 0f },
        count         = 1
    )

    private fun syntheticNorm(centroid: FloatArray, noise: Float, seed: Int): FloatArray {
        val rng = java.util.Random(seed.toLong())
        val v = FloatArray(centroid.size) { i -> centroid[i] + (rng.nextFloat() - 0.5f) * noise }
        return VectorUtils.normalize(v)
    }

    // ─────────────── Face DB: insertion & retrieval ───────────────

    @Test
    fun insertFace_canBeRetrievedByMediaId() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        db.faceDao().insertFace(makeFace(1L))

        val faces = db.faceDao().getFacesForMedia(1L)
        assertEquals(1, faces.size)
    }

    @Test
    fun insertMultipleFaces_sameMedia_allPersisted() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        db.faceDao().insertFace(makeFace(1L, seed = 0))
        db.faceDao().insertFace(makeFace(1L, seed = 1))
        db.faceDao().insertFace(makeFace(1L, seed = 2))

        assertEquals(3, db.faceDao().getFacesForMedia(1L).size)
    }

    // ─────────────── Face DB: cascade delete ───────────────

    @Test
    fun deleteMedia_cascadesDeleteToFaces() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(10L)))
        db.faceDao().insertFace(makeFace(10L))
        db.faceDao().insertFace(makeFace(10L, seed = 1))

        // Deleting the media item should cascade to its faces via FK
        db.mediaDao().delete(makeMedia(10L))

        val remaining = db.faceDao().getFacesForMedia(10L)
        assertTrue("Faces should be cascade-deleted with their media", remaining.isEmpty())
    }

    @Test
    fun deleteFacesForMedia_onlyRemovesTargetMedia() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L), makeMedia(2L)))
        db.faceDao().insertFace(makeFace(1L))
        db.faceDao().insertFace(makeFace(2L))

        db.faceDao().deleteFacesForMedia(1L)

        assertTrue(db.faceDao().getFacesForMedia(1L).isEmpty())
        assertEquals(1, db.faceDao().getFacesForMedia(2L).size)
    }

    // ─────────────── Face DB: person assignment ───────────────

    @Test
    fun updateFacePersonId_assignsFaceToCorrectPerson() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        db.personDao().insertPerson(makePerson(99L))
        val faceId = db.faceDao().insertFace(makeFace(1L))

        db.faceDao().updateFacePersonId(faceId, 99L)

        val face = db.faceDao().getAllFaces().first { it.faceId == faceId }
        assertEquals(99L, face.personId)
    }

    @Test
    fun clearAllPersonAssignments_nullifiesPersonIds() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        db.personDao().insertPerson(makePerson(1L))
        val faceId = db.faceDao().insertFace(makeFace(1L))
        db.faceDao().updateFacePersonId(faceId, 1L)

        db.faceDao().clearAllPersonAssignments()

        val faces = db.faceDao().getAllFaces()
        assertTrue("All person assignments should be cleared", faces.all { it.personId == null })
    }

    // ─────────────── Chinese Whispers: algorithm correctness ───────────────

    @Test
    fun clustering_emptyInput_returnsEmptyList() {
        assertTrue(ChineseWhispers.cluster(emptyList()).isEmpty())
    }

    @Test
    fun clustering_singleFace_returnsSingleCluster() {
        val emb = VectorUtils.normalize(floatArrayOf(1f, 0f, 0f, 0f))
        val result = ChineseWhispers.cluster(listOf(emb))
        assertEquals(1, result.size)
        assertEquals(0, result[0][0])
    }

    @Test
    fun clustering_twoDistinctGroups_producesExactlyTwoClusters() {
        val dim = 128
        // Two groups pointing in completely orthogonal directions → similarity ≈ 0
        val c1 = FloatArray(dim).also { it[0] = 1f }
        val c2 = FloatArray(dim).also { it[1] = 1f }

        val embeddings = (0 until 6).map { syntheticNorm(c1, 0.02f, it) } +
                         (6 until 12).map { syntheticNorm(c2, 0.02f, it) }

        val clusters = ChineseWhispers.cluster(embeddings)
        assertEquals("Should produce exactly 2 clusters", 2, clusters.size)
        for (c in clusters) assertEquals("Each cluster should contain 6 faces", 6, c.size)
    }

    @Test
    fun clustering_noIndexLostOrDuplicated() {
        val dim = 128
        val c1 = FloatArray(dim).also { it[0] = 1f }
        val c2 = FloatArray(dim).also { it[1] = 1f }
        val c3 = FloatArray(dim).also { it[2] = 1f }

        val embeddings = (0 until 5).map { syntheticNorm(c1, 0.02f, it) } +
                         (5 until 10).map { syntheticNorm(c2, 0.02f, it) } +
                         (10 until 15).map { syntheticNorm(c3, 0.02f, it) }

        val clusters = ChineseWhispers.cluster(embeddings)
        val allIndices = clusters.flatten()

        assertEquals("Total count must equal input size", 15, allIndices.size)
        assertEquals("No index should appear twice", 15, allIndices.toSet().size)
    }

    // ─────────────── Person DAO ───────────────

    @Test
    fun insertPerson_canBeRetrievedById() = runBlocking {
        db.personDao().insertPerson(makePerson(1L, "Alice"))
        val person = db.personDao().getPersonById(1L)
        assertNotNull(person)
        assertEquals("Alice", person!!.name)
    }

    @Test
    fun incrementDecrement_personCounter() = runBlocking {
        db.personDao().insertPerson(makePerson(1L))
        db.personDao().incrementPersonCounter(1L)
        db.personDao().incrementPersonCounter(1L)
        val after = db.personDao().getPersonById(1L)!!
        assertEquals(3, after.count) // starts at 1

        db.personDao().decrementPersonCounter(1L)
        val decremented = db.personDao().getPersonById(1L)!!
        assertEquals(2, decremented.count)
    }

    @Test
    fun getPersonsByNames_returnsMediaWithBothPersonsPresent() = runBlocking {
        // Set up: 3 media items, persons Alice and Bob
        db.mediaDao().insertAll(listOf(makeMedia(1L), makeMedia(2L), makeMedia(3L)))
        db.personDao().insertPerson(makePerson(1L, "Alice"))
        db.personDao().insertPerson(makePerson(2L, "Bob"))

        // Face from Alice in media 1 and 2; Bob only in media 1
        val f1 = db.faceDao().insertFace(makeFace(1L, seed = 0))
        val f2 = db.faceDao().insertFace(makeFace(2L, seed = 1))
        val f3 = db.faceDao().insertFace(makeFace(1L, seed = 2))

        db.faceDao().updateFacePersonId(f1, 1L)  // Alice in media 1
        db.faceDao().updateFacePersonId(f2, 1L)  // Alice in media 2
        db.faceDao().updateFacePersonId(f3, 2L)  // Bob in media 1

        // Only media 1 has both Alice and Bob
        val results = db.personDao().getImagesByNames(listOf("Alice", "Bob"), 2)
        assertEquals(1, results.size)
        assertEquals(1L, results.first().mediaId)
    }

    // ───────────────── FaceDao: batch insert & count ─────────────────

    @Test
    fun insertFaces_batch_allPersisted() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        val faces = (0 until 5).map { makeFace(1L, seed = it) }
        db.faceDao().insertFaces(faces)

        assertEquals(5, db.faceDao().countFaces())
    }

    @Test
    fun countFaces_returnsZeroOnEmptyTable() = runBlocking {
        assertEquals(0, db.faceDao().countFaces())
    }

    @Test
    fun countFaces_incrementsAfterEachInsert() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(1L)))
        repeat(3) { db.faceDao().insertFace(makeFace(1L, seed = it)) }
        assertEquals(3, db.faceDao().countFaces())
    }
}
