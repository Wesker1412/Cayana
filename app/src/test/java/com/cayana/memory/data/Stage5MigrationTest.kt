package com.cayana.memory.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.search.data.SearchDao
import com.cayana.source.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage5MigrationTest {

    private val TEST_DB = "stage5-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CayanaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateStage4ToStage5PreservesMemories() {
        var db = helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('item-v4-1', 'SCREENSHOT', 1000, 1000, 'Screenshot Note', 'Invoice total NT$5000', 'Invoice total NT$5000', 'content://media/1', NULL, 1, '{\"displayName\":\"invoice.png\"}', '[]', '[]', 'COMPLETED')"
            )
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('item-v4-2', 'RECORDING', 2000, 2000, 'Meeting Memo', 'Discuss Q3 roadmap', 'Discuss Q3 roadmap', 'content://media/2', NULL, 1, '{\"durationMs\":\"12000\"}', '[]', '[]', 'COMPLETED')"
            )
            close()
        }

        // Run migration 4 -> 5
        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, CayanaDatabase.MIGRATION_4_5)

        // Validate memories table preserves all records and columns
        val cursor = db.query("SELECT id, title, rawText, sourceType FROM memories ORDER BY capturedAt ASC")
        assertTrue("Migrated memory rows must exist", cursor.moveToFirst())
        assertEquals("item-v4-1", cursor.getString(0))
        assertEquals("Screenshot Note", cursor.getString(1))
        assertEquals("Invoice total NT$5000", cursor.getString(2))
        assertEquals("SCREENSHOT", cursor.getString(3))

        assertTrue(cursor.moveToNext())
        assertEquals("item-v4-2", cursor.getString(0))
        assertEquals("Meeting Memo", cursor.getString(1))
        assertEquals("Discuss Q3 roadmap", cursor.getString(2))
        assertEquals("RECORDING", cursor.getString(3))
        cursor.close()
    }

    @Test
    fun migrationBackfillsExistingMemoriesIntoSearch() {
        var db = helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('cayana-rec-1', 'RECORDING', 1000, 1000, '語音記事', '明天上午十點跟老王開會討論 Cayana 架構', '明天上午十點跟老王開會討論 Cayana 架構', 'content://media/rec1', NULL, 1, '{\"displayName\":\"rec_001.m4a\"}', '[]', '[]', 'COMPLETED')"
            )
            close()
        }

        // Run migration 4 -> 5
        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, CayanaDatabase.MIGRATION_4_5)

        // Verify FTS table exists and query match
        val ftsCursor = db.query("SELECT memoryId, title, searchTokens FROM memories_fts WHERE memories_fts MATCH 'Cayana'")
        assertTrue("FTS index must backfill existing memories and be searchable", ftsCursor.moveToFirst())
        assertEquals("cayana-rec-1", ftsCursor.getString(0))
        assertEquals("語音記事", ftsCursor.getString(1))
        ftsCursor.close()
    }

    @Test
    fun searchIndexFailureDoesNotLoseMemory() = runBlocking {
        val inMemoryDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()

        // Create repository with a broken searchDao that fails
        val failingSearchDao = object : SearchDao {
            override suspend fun insertFts(entity: MemoryFtsEntity) {
                throw IllegalStateException("Simulated FTS disk failure")
            }
            override suspend fun insertAllFts(entities: List<MemoryFtsEntity>) {
                throw IllegalStateException("Simulated FTS disk failure")
            }
            override suspend fun deleteFtsByMemoryId(memoryId: String) {
                throw IllegalStateException("Simulated FTS disk failure")
            }
            override suspend fun clearFts() {
                throw IllegalStateException("Simulated FTS disk failure")
            }
            override suspend fun getFtsCount(): Int = 0

            override fun searchMemoriesMatchFlow(ftsQuery: String): kotlinx.coroutines.flow.Flow<List<MemoryEntity>> =
                kotlinx.coroutines.flow.flowOf(emptyList())

            override suspend fun searchMemoriesMatch(ftsQuery: String): List<MemoryEntity> =
                emptyList()

            override suspend fun searchMemoryIds(ftsQuery: String): List<String> =
                emptyList()
        }

        val repository = RoomMemoryRepository(
            memoryDao = inMemoryDb.memoryDao(),
            searchDao = failingSearchDao
        )

        val memoryItem = MemoryItem(
            id = "resilient-id-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Resilient Note",
            rawText = "Important information that must not be lost",
            normalizedText = "Important information that must not be lost",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )

        // Saving memory should NOT throw despite FTS failure
        repository.saveMemory(memoryItem)

        // Canonical memory must be persisted safely
        val retrieved = repository.getMemoryById("resilient-id-1").first()
        assertNotNull("Canonical memory must be persisted even if FTS index fails", retrieved)
        assertEquals("Resilient Note", retrieved?.title)
        assertTrue("Repository must flag index rebuild needed", repository.isIndexRebuildNeeded())

        inMemoryDb.close()
    }
}
