package com.cayana.memory.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CayanaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2() {
        var db = helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('item-1', 'SCREENSHOT', 1000, 1000, 'Test', 'Sample', 'Sample', 'content://test', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )
            close()
        }

        // Re-open database with version 2 and run migration 1 -> 2
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, CayanaDatabase.MIGRATION_1_2)

        // Verify index_memories_sourceUri exists on memories table
        val cursor = db.query("PRAGMA index_list('memories')")
        var hasSourceUriIndex = false
        while (cursor.moveToNext()) {
            val nameCol = cursor.getColumnIndex("name")
            if (nameCol >= 0) {
                val name = cursor.getString(nameCol)
                if (name == "index_memories_sourceUri") {
                    hasSourceUriIndex = true
                    break
                }
            }
        }
        cursor.close()
        assertTrue("index_memories_sourceUri must exist after migration 1 -> 2", hasSourceUriIndex)
    }

    @Test
    fun migrate2To3() {
        var db = helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('item-1', 'SCREENSHOT', 1000, 1000, 'Test', 'Sample', 'Sample', 'content://test', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )
            close()
        }

        // Re-open database with version 3 and run migration 2 -> 3
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, CayanaDatabase.MIGRATION_2_3)

        // Verify calendar_actions table exists
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='calendar_actions'")
        assertTrue("calendar_actions table must exist after migration 2 -> 3", cursor.moveToFirst())
        cursor.close()

        // Verify index_calendar_actions_memoryId_actionType exists
        val indexCursor = db.query("PRAGMA index_list('calendar_actions')")
        var hasUniqueIndex = false
        var hasMemoryIndex = false
        while (indexCursor.moveToNext()) {
            val nameCol = indexCursor.getColumnIndex("name")
            if (nameCol >= 0) {
                val name = indexCursor.getString(nameCol)
                if (name == "index_calendar_actions_memoryId_actionType") hasUniqueIndex = true
                if (name == "index_calendar_actions_memoryId") hasMemoryIndex = true
            }
        }
        indexCursor.close()
        assertTrue("index_calendar_actions_memoryId_actionType must exist", hasUniqueIndex)
        assertTrue("index_calendar_actions_memoryId must exist", hasMemoryIndex)

        // Verify we can insert into calendar_actions
        db.execSQL(
            "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt) " +
            "VALUES ('act-1', 'item-1', 1, 100, 'AUTO_CREATED', 2000, 'CREATED', 'Test Event', 3000, 4000)"
        )
    }

    @Test
    fun migrate3To4() {
        var db = helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('item-1', 'SCREENSHOT', 1000, 1000, 'Test', 'Sample', 'Sample', 'content://test', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )
            execSQL(
                "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt) " +
                "VALUES ('act-v3', 'item-1', 1, 100, 'AUTO_CREATED', 2000, 'CREATED', 'Test Event', 3000, 4000)"
            )
            close()
        }

        // Re-open database with version 4 and run migration 3 -> 4
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, CayanaDatabase.MIGRATION_3_4)

        // Verify existing row has default values for newly added columns
        val cursor = db.query("SELECT location, isAllDay, zoneId FROM calendar_actions WHERE id='act-v3'")
        assertTrue("Migrated row must exist", cursor.moveToFirst())
        assertNull("location should default to NULL", cursor.getString(0))
        assertEquals("isAllDay should default to 0", 0, cursor.getInt(1))
        assertNull("zoneId should default to NULL", cursor.getString(2))
        cursor.close()

        // Verify we can insert a new row with version 4 columns
        db.execSQL(
            "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt, location, isAllDay, zoneId) " +
            "VALUES ('act-v4', 'item-1', 1, 101, 'CONFIRM_PENDING', 2100, 'PENDING', 'V4 Event', 5000, 6000, 'Taipei 101', 1, 'Asia/Taipei')"
        )

        val v4Cursor = db.query("SELECT location, isAllDay, zoneId FROM calendar_actions WHERE id='act-v4'")
        assertTrue(v4Cursor.moveToFirst())
        assertEquals("Taipei 101", v4Cursor.getString(0))
        assertEquals(1, v4Cursor.getInt(1))
        assertEquals("Asia/Taipei", v4Cursor.getString(2))
        v4Cursor.close()
    }
}
