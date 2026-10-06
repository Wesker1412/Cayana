package com.cayana.memory.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage6MigrationTest {

    private val TEST_DB = "stage6-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CayanaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateStage5V6ToStage6V7PreservesAllExistingTables() {
        var db = helper.createDatabase(TEST_DB, 6).apply {
            // 1. Existing memory
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('mem-v6-1', 'SCREENSHOT', 1000, 1000, '高鐵票', '車次0123台北至左營', '車次0123台北至左營', 'content://media/1', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )

            // 2. Existing calendar_action
            execSQL(
                "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt, location, isAllDay, zoneId) " +
                "VALUES ('action-v6-1', 'mem-v6-1', 1, 999, 'CREATE_EVENT', 1000, 'CONFIRMED', '高鐵0123', 2000, 3000, '台北車站', 0, 'Asia/Taipei')"
            )

            // 3. Existing share receipt
            execSQL(
                "INSERT INTO share_receipts (fingerprint, sessionId, createdAt, expiresAt, status, itemIdsJson) " +
                "VALUES ('fp-123', 'session-123', 1000, 5000, 'COMPLETED', '[\"mem-v6-1\"]')"
            )

            // 4. Existing search_index_state
            execSQL(
                "INSERT OR REPLACE INTO search_index_state (id, isDirty, pendingRepairs, lastUpdated) " +
                "VALUES (1, 0, 0, 1000)"
            )

            // 5. Existing memories_fts
            execSQL(
                "INSERT INTO memories_fts (memoryId, title, rawText, normalizedText, sourceType, sourceUrl, host, displayName, searchTokens) " +
                "VALUES ('mem-v6-1', '高鐵票', '車次0123台北至左營', '車次0123台北至左營', 'SCREENSHOT', '', '', '', '高鐵票 車次0123台北至左營')"
            )

            close()
        }

        // Run migration v6 -> v7
        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, CayanaDatabase.MIGRATION_6_7)

        // Validate memories table preserved
        val memCursor = db.query("SELECT id, title, rawText FROM memories WHERE id = 'mem-v6-1'")
        assertTrue("Memory must be preserved", memCursor.moveToFirst())
        assertEquals("mem-v6-1", memCursor.getString(0))
        assertEquals("高鐵票", memCursor.getString(1))
        memCursor.close()

        // Validate calendar_actions table preserved
        val actCursor = db.query("SELECT id, title, calendarEventId FROM calendar_actions WHERE id = 'action-v6-1'")
        assertTrue("CalendarAction must be preserved", actCursor.moveToFirst())
        assertEquals("action-v6-1", actCursor.getString(0))
        assertEquals(999L, actCursor.getLong(2))
        actCursor.close()

        // Validate share_receipts preserved
        val receiptCursor = db.query("SELECT fingerprint, status FROM share_receipts WHERE fingerprint = 'fp-123'")
        assertTrue("ShareReceipt must be preserved", receiptCursor.moveToFirst())
        assertEquals("COMPLETED", receiptCursor.getString(1))
        receiptCursor.close()

        // Validate search_index_state preserved
        val searchStateCursor = db.query("SELECT id, isDirty FROM search_index_state WHERE id = 1")
        assertTrue("SearchState must be preserved", searchStateCursor.moveToFirst())
        assertEquals(0, searchStateCursor.getInt(1))
        searchStateCursor.close()

        // Validate memories_fts preserved
        val ftsCursor = db.query("SELECT memoryId, title FROM memories_fts WHERE memoryId = 'mem-v6-1'")
        assertTrue("FTS entry must be preserved", ftsCursor.moveToFirst())
        assertEquals("高鐵票", ftsCursor.getString(1))
        ftsCursor.close()

        // Validate new table restored_calendar_action_history exists and is writable
        db.execSQL(
            "INSERT INTO restored_calendar_action_history (id, originalActionId, memoryId, actionType, originalStatus, createdAt, title, startAt, endAt, location, isAllDay, zoneId, originalCalendarId, originalCalendarEventId, restoredAt) " +
            "VALUES ('restored_action-v6-1', 'action-v6-1', 'mem-v6-1', 'CREATE_EVENT', 'CONFIRMED', 1000, '高鐵0123', 2000, 3000, '台北車站', 0, 'Asia/Taipei', 1, 999, 5000)"
        )
        val restoredCursor = db.query("SELECT originalActionId, originalCalendarEventId FROM restored_calendar_action_history WHERE id = 'restored_action-v6-1'")
        assertTrue("RestoredCalendarActionHistory row must exist", restoredCursor.moveToFirst())
        assertEquals("action-v6-1", restoredCursor.getString(0))
        assertEquals(999L, restoredCursor.getLong(1))
        restoredCursor.close()
    }
}
