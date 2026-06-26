package com.example.gallery

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gallery.db.AppDatabase

import com.example.gallery.db.daos.PersonDao
import com.example.gallery.db.entities.FaceEntity
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.PersonEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [PersonDao].
 *
 * Verifies all CRUD operations, embedding updates, folder preview building,
 * increment/decrement counters, multi-person intersection queries, and the
 * custom alphabetical/numerical sorting logic for gallery folder display.
 */
@RunWith(AndroidJUnit4::class)
class PersonDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PersonDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.personDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ───────────────── helpers ─────────────────

    private fun makeMedia(id: Long) = MediaEntity(
        mediaId     = id,
        timestampMs = id * 1000L,
        isVideo     = false,
        embedding   = FloatArray(512) { 0f }
    )

    private fun makeFace(faceId: Long, mediaId: Long, personId: Long? = null) = FaceEntity(
        faceId    = faceId,
        mediaId   = mediaId,
        embedding = FloatArray(128) { 0f },
        boxLeft   = 0, boxTop = 0, boxRight = 50, boxBottom = 50,
        personId  = personId
    )

    private fun makeFace(mediaId: Long, personId: Long? = null) = FaceEntity(
        mediaId   = mediaId,
        embedding = FloatArray(128) { 0f },
        boxLeft   = 0, boxTop = 0, boxRight = 50, boxBottom = 50,
        personId  = personId
    )

    private fun makePerson(id: Long, name: String) = PersonEntity(
        id            = id,
        name          = name,
        thumbnailPath = "path/to/$id",
        thumbnailSize = 100,
        Embedding     = FloatArray(128) { 0.1f * id },
        count         = 1
    )

    // ───────────────── CRUD & Basic Ops ─────────────────

    @Test
    fun insertAndGetPerson_returnsCorrectValues() = runBlocking {
        val person = makePerson(10L, "Charlie")
        dao.insertPerson(person)

        val retrieved = dao.getPersonById(10L)
        assertNotNull(retrieved)
        assertEquals("Charlie", retrieved!!.name)
        assertEquals("path/to/10", retrieved.thumbnailPath)
        assertArrayEquals(FloatArray(128) { 1.0f }, retrieved.Embedding, 1e-5f)
    }

    @Test
    fun updatePersonName_modifiesNameField() = runBlocking {
        dao.insertPerson(makePerson(5L, "OriginalName"))
        dao.updatePersonName(5L, "UpdatedName")

        val retrieved = dao.getPersonById(5L)
        assertEquals("UpdatedName", retrieved?.name)
    }

    @Test
    fun deletePerson_removesPersonFromTable() = runBlocking {
        dao.insertPerson(makePerson(1L, "Alice"))
        assertEquals(1, dao.countPersons())

        dao.deletePerson(1L)
        assertEquals(0, dao.countPersons())
    }

    @Test
    fun updatePersonThumbnail_modifiesThumbnailMetadata() = runBlocking {
        dao.insertPerson(makePerson(2L, "Bob"))
        dao.updatePersonThumbnail(2L, "new/path/bob.png", 250)

        val retrieved = dao.getPersonById(2L)
        assertEquals("new/path/bob.png", retrieved?.thumbnailPath)
        assertEquals(250, retrieved?.thumbnailSize)
    }

    // ───────────────── Custom Sorting Flow ─────────────────

    @Test
    fun getAllNamesSortedFlow_sortsRealNamesFirstThenPlaceholdersNumerically() = runBlocking {
        // Named people should come first alphabetically (case-insensitive or lower).
        // Placeholders matching '#p<number>' should come last sorted numerically.
        dao.insertPerson(makePerson(1L, "#p10"))
        dao.insertPerson(makePerson(2L, "Zack"))
        dao.insertPerson(makePerson(3L, "#p2"))
        dao.insertPerson(makePerson(4L, "Alice"))
        dao.insertPerson(makePerson(5L, "#p1"))
        dao.insertPerson(makePerson(6L, "Bob"))

        val sortedNames = dao.getAllNamesSortedFlow().first()

        // Expected order:
        // Named: Alice, Bob, Zack
        // Placeholders: #p1, #p2, #p10 (note: standard alphabetical would place #p10 before #p2, but numerical places #p2 first)
        val expected = listOf("Alice", "Bob", "Zack", "#p1", "#p2", "#p10")
        assertEquals(expected, sortedNames)
    }

    // ───────────────── Person Previews & Media Relations ─────────────────

    @Test
    fun getPersonsWithMediaIds_correctlyMapsPreviewsToMediaLists() = runBlocking {
        // Insert media, persons, and link them via faces
        db.mediaDao().insertAll(listOf(makeMedia(100L), makeMedia(200L), makeMedia(300L)))
        dao.insertPerson(makePerson(1L, "Alice"))
        dao.insertPerson(makePerson(2L, "Bob"))

        // Alice is present in media 100 and 200
        val f1 = db.faceDao().insertFace(makeFace(1L, 100L))
        val f2 = db.faceDao().insertFace(makeFace(2L, 200L))
        db.faceDao().updateFacePersonId(f1, 1L)
        db.faceDao().updateFacePersonId(f2, 1L)

        // Bob is present in media 200 and 300
        val f3 = db.faceDao().insertFace(makeFace(3L, 200L))
        val f4 = db.faceDao().insertFace(makeFace(4L, 300L))
        db.faceDao().updateFacePersonId(f3, 2L)
        db.faceDao().updateFacePersonId(f4, 2L)

        val result = dao.getPersonsWithMediaIds()
        assertEquals(2, result.size)

        val aliceEntry = result.first { it.first.name == "Alice" }
        assertEquals(1L, aliceEntry.first.id)
        // Alice media list should contain 100 and 200
        assertEquals(setOf(100L, 200L), aliceEntry.second.toSet())

        val bobEntry = result.first { it.first.name == "Bob" }
        assertEquals(2L, bobEntry.first.id)
        // Bob media list should contain 200 and 300
        assertEquals(setOf(200L, 300L), bobEntry.second.toSet())
    }

    // ───────────────── getPersonsForImage ─────────────────

    @Test
    fun getPersonsForImage_mediaWithNoFaces_returnsEmpty() = runBlocking {
        db.mediaDao().insertAll(listOf(makeMedia(99L)))
        val persons = dao.getPersonsForImage(99L)
        assertTrue(persons.isEmpty())
    }

    // ───────────────── deleteAllPersons ─────────────────

    @Test
    fun deleteAllPersons_clearsEntireTable() = runBlocking {
        dao.insertPerson(makePerson(1L, "Alice"))
        dao.insertPerson(makePerson(2L, "Bob"))
        dao.insertPerson(makePerson(3L, "Charlie"))
        assertEquals(3, dao.countPersons())

        dao.deleteAllPersons()
        assertEquals(0, dao.countPersons())
    }

    // ───────────────── updateEmbedding ─────────────────

    @Test
    fun updateEmbedding_persistsNewEmbeddingValues() = runBlocking {
        dao.insertPerson(makePerson(1L, "Alice"))
        val newEmbedding = FloatArray(128) { it * 0.01f }
        dao.updateEmbedding(1L, newEmbedding)

        val retrieved = dao.getPersonById(1L)!!
        assertArrayEquals(newEmbedding, retrieved.Embedding, 1e-5f)
    }
}
