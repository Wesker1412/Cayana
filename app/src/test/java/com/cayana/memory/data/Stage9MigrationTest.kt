package com.cayana.memory.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage9MigrationTest {

    private val TEST_DB = "stage9-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CayanaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateV8ToV9DropsCloudTablesAndPreservesAllCanonicalData() {
        var db = helper.createDatabase(TEST_DB, 8).apply {
            // 1. Insert canonical memory
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('mem-v8-1', 'SCREENSHOT', 1000, 1000, '台鐵票', '車次0456台北至花蓮', '車次0456台北至花蓮', 'content://media/1', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )

            // 2. Insert calendar_actions
            execSQL(
                "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt, location, isAllDay, zoneId) " +
                "VALUES ('action-v8-1', 'mem-v8-1', 1, 999, 'CREATE_EVENT', 1000, 'CONFIRMED', '台鐵0456', 2000, 3000, '台北車站', 0, 'Asia/Taipei')"
            )

            // 3. Insert restored_calendar_action_history
            execSQL(
                "INSERT INTO restored_calendar_action_history (id, originalActionId, memoryId, actionType, originalStatus, createdAt, title, startAt, endAt, location, isAllDay, zoneId, originalCalendarId, originalCalendarEventId, restoredAt) " +
                "VALUES ('hist-v8-1', 'action-v8-1', 'mem-v8-1', 'CREATE_EVENT', 'CONFIRMED', 1000, '台鐵0456', 2000, 3000, '台北車站', 0, 'Asia/Taipei', 1, 999, 5000)"
            )

            // 4. Insert share receipt
            execSQL(
                "INSERT INTO share_receipts (fingerprint, sessionId, createdAt, expiresAt, status, itemIdsJson) " +
                "VALUES ('fp-v8', 'session-v8', 1000, 5000, 'COMPLETED', '[\"mem-v8-1\"]')"
            )

            // 5. Insert search_index_state
            execSQL(
                "INSERT OR REPLACE INTO search_index_state (id, isDirty, pendingRepairs, lastUpdated) " +
                "VALUES (1, 0, 0, 1000)"
            )

            // 6. Insert memories_fts
            execSQL(
                "INSERT INTO memories_fts (memoryId, title, rawText, normalizedText, sourceType, sourceUrl, host, displayName, searchTokens) " +
                "VALUES ('mem-v8-1', '台鐵票', '車次0456台北至花蓮', '車次0456台北至花蓮', 'SCREENSHOT', '', '', '', '台鐵票 車次0456台北至花蓮')"
            )

            // 7. Insert v8 cloud tables data
            execSQL(
                "INSERT INTO cloud_sync_state (id, isInitialized, isEnabled, lastPullSeq, lastSuccessfulSyncAt, lastErrorCode) " +
                "VALUES (1, 1, 1, 42, 9000, NULL)"
            )
            execSQL(
                "INSERT INTO cloud_memory_sync_metadata (memoryId, revision, lastSyncedRevision) " +
                "VALUES ('mem-v8-1', 2, 2)"
            )
            execSQL(
                "INSERT INTO cloud_sync_outbox (id, memoryId, revision, operation, createdAt, attemptCount) " +
                "VALUES ('ob-1', 'mem-v8-1', 2, 'UPSERT', 1000, 0)"
            )

            close()
        }

        // Run migration v8 -> v9
        db = helper.runMigrationsAndValidate(TEST_DB, 9, true, CayanaDatabase.MIGRATION_8_9)

        // 1. Verify memories preserved
        val memCursor = db.query("SELECT id, title, rawText FROM memories WHERE id = 'mem-v8-1'")
        assertTrue("Memory must be preserved across v8->v9 migration", memCursor.moveToFirst())
        assertEquals("mem-v8-1", memCursor.getString(0))
        assertEquals("台鐵票", memCursor.getString(1))
        memCursor.close()

        // 2. Verify calendar_actions preserved
        val actCursor = db.query("SELECT id, title, calendarEventId FROM calendar_actions WHERE id = 'action-v8-1'")
        assertTrue("CalendarAction must be preserved across v8->v9 migration", actCursor.moveToFirst())
        assertEquals("action-v8-1", actCursor.getString(0))
        assertEquals(999L, actCursor.getLong(2))
        actCursor.close()

        // 3. Verify restored_calendar_action_history preserved
        val histCursor = db.query("SELECT id, originalActionId, memoryId FROM restored_calendar_action_history WHERE id = 'hist-v8-1'")
        assertTrue("Restored history must be preserved across v8->v9 migration", histCursor.moveToFirst())
        assertEquals("hist-v8-1", histCursor.getString(0))
        assertEquals("action-v8-1", histCursor.getString(1))
        histCursor.close()

        // 4. Verify share_receipts preserved
        val receiptCursor = db.query("SELECT fingerprint, status FROM share_receipts WHERE fingerprint = 'fp-v8'")
        assertTrue("ShareReceipt must be preserved across v8->v9 migration", receiptCursor.moveToFirst())
        assertEquals("COMPLETED", receiptCursor.getString(1))
        receiptCursor.close()

        // 5. Verify search_index_state preserved
        val searchStateCursor = db.query("SELECT id, isDirty FROM search_index_state WHERE id = 1")
        assertTrue("Search index state must be preserved", searchStateCursor.moveToFirst())
        assertEquals(0, searchStateCursor.getInt(1))
        searchStateCursor.close()

        // 6. Verify memories_fts preserved
        val ftsCursor = db.query("SELECT memoryId, title FROM memories_fts WHERE memoryId = 'mem-v8-1'")
        assertTrue("Memories FTS must be preserved", ftsCursor.moveToFirst())
        assertEquals("台鐵票", ftsCursor.getString(1))
        ftsCursor.close()

        // 7. Verify all 3 cloud tables are DROPPED
        val droppedTablesCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('cloud_sync_state', 'cloud_memory_sync_metadata', 'cloud_sync_outbox')"
        )
        assertFalse("All 3 cloud tables must be dropped in v9", droppedTablesCursor.moveToFirst())
        droppedTablesCursor.close()
    }
}
