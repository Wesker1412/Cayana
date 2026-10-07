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
class Stage7MigrationTest {

    private val TEST_DB = "stage7-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CayanaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateStage6V7ToStage7V8PreservesAllExistingTables() {
        var db = helper.createDatabase(TEST_DB, 7).apply {
            // 1. Existing memory
            execSQL(
                "INSERT INTO memories (id, sourceType, createdAt, capturedAt, title, rawText, normalizedText, sourceUri, sourceUrl, sourceExists, metadataJson, entitiesJson, eventCandidatesJson, processingState) " +
                "VALUES ('mem-v7-1', 'SCREENSHOT', 1000, 1000, '台鐵票', '車次0456台北至花蓮', '車次0456台北至花蓮', 'content://media/1', NULL, 1, '{}', '[]', '[]', 'COMPLETED')"
            )

            // 2. Existing calendar_actions
            execSQL(
                "INSERT INTO calendar_actions (id, memoryId, calendarId, calendarEventId, actionType, createdAt, status, title, startAt, endAt, location, isAllDay, zoneId) " +
                "VALUES ('action-v7-1', 'mem-v7-1', 1, 999, 'CREATE_EVENT', 1000, 'CONFIRMED', '台鐵0456', 2000, 3000, '台北車站', 0, 'Asia/Taipei')"
            )

            // 3. Existing restored_calendar_action_history
            execSQL(
                "INSERT INTO restored_calendar_action_history (id, originalActionId, memoryId, actionType, originalStatus, createdAt, title, startAt, endAt, location, isAllDay, zoneId, originalCalendarId, originalCalendarEventId, restoredAt) " +
                "VALUES ('hist-v7-1', 'action-v7-1', 'mem-v7-1', 'CREATE_EVENT', 'CONFIRMED', 1000, '台鐵0456', 2000, 3000, '台北車站', 0, 'Asia/Taipei', 1, 999, 5000)"
            )

            // 4. Existing share receipt
            execSQL(
                "INSERT INTO share_receipts (fingerprint, sessionId, createdAt, expiresAt, status, itemIdsJson) " +
                "VALUES ('fp-v7', 'session-v7', 1000, 5000, 'COMPLETED', '[\"mem-v7-1\"]')"
            )

            // 5. Existing search_index_state
            execSQL(
                "INSERT OR REPLACE INTO search_index_state (id, isDirty, pendingRepairs, lastUpdated) " +
                "VALUES (1, 0, 0, 1000)"
            )

            // 6. Existing memories_fts
            execSQL(
                "INSERT INTO memories_fts (memoryId, title, rawText, normalizedText, sourceType, sourceUrl, host, displayName, searchTokens) " +
                "VALUES ('mem-v7-1', '台鐵票', '車次0456台北至花蓮', '車次0456台北至花蓮', 'SCREENSHOT', '', '', '', '台鐵票 車次0456台北至花蓮')"
            )

            close()
        }

        // Run migration v7 -> v8
        db = helper.runMigrationsAndValidate(TEST_DB, 8, true, CayanaDatabase.MIGRATION_7_8)

        // Validate memories preserved
        val memCursor = db.query("SELECT id, title, rawText FROM memories WHERE id = 'mem-v7-1'")
        assertTrue("Memory must be preserved across migration", memCursor.moveToFirst())
        assertEquals("mem-v7-1", memCursor.getString(0))
        assertEquals("台鐵票", memCursor.getString(1))
        memCursor.close()

        // Validate calendar_actions preserved
        val actCursor = db.query("SELECT id, title, calendarEventId FROM calendar_actions WHERE id = 'action-v7-1'")
        assertTrue("CalendarAction must be preserved", actCursor.moveToFirst())
        assertEquals("action-v7-1", actCursor.getString(0))
        assertEquals(999L, actCursor.getLong(2))
        actCursor.close()

        // Validate restored_calendar_action_history preserved
        val histCursor = db.query("SELECT id, originalActionId, memoryId FROM restored_calendar_action_history WHERE id = 'hist-v7-1'")
        assertTrue("Restored history must be preserved", histCursor.moveToFirst())
        assertEquals("hist-v7-1", histCursor.getString(0))
        assertEquals("action-v7-1", histCursor.getString(1))
        assertEquals("mem-v7-1", histCursor.getString(2))
        histCursor.close()

        // Validate share_receipts preserved
        val receiptCursor = db.query("SELECT fingerprint, status FROM share_receipts WHERE fingerprint = 'fp-v7'")
        assertTrue("ShareReceipt must be preserved", receiptCursor.moveToFirst())
        assertEquals("COMPLETED", receiptCursor.getString(1))
        receiptCursor.close()

        // Validate search_index_state preserved
        val searchStateCursor = db.query("SELECT id, isDirty FROM search_index_state WHERE id = 1")
        assertTrue("SearchState must be preserved", searchStateCursor.moveToFirst())
        assertEquals(0, searchStateCursor.getInt(1))
        searchStateCursor.close()

        // Validate memories_fts preserved
        val ftsCursor = db.query("SELECT memoryId, title FROM memories_fts WHERE memoryId = 'mem-v7-1'")
        assertTrue("FTS entry must be preserved", ftsCursor.moveToFirst())
        assertEquals("台鐵票", ftsCursor.getString(1))
        ftsCursor.close()

        // Validate new table cloud_sync_state is operational
        db.execSQL(
            "INSERT INTO cloud_sync_state (id, isInitialized, isEnabled, lastPullSeq, lastSuccessfulSyncAt, lastErrorCode) " +
            "VALUES (1, 1, 1, 42, 9999, NULL)"
        )
        val cloudStateCursor = db.query("SELECT isInitialized, isEnabled, lastPullSeq FROM cloud_sync_state WHERE id = 1")
        assertTrue("cloud_sync_state table must be created and writable", cloudStateCursor.moveToFirst())
        assertEquals(1, cloudStateCursor.getInt(0))
        assertEquals(1, cloudStateCursor.getInt(1))
        assertEquals(42L, cloudStateCursor.getLong(2))
        cloudStateCursor.close()

        // Validate new table cloud_memory_sync_metadata is operational
        db.execSQL(
            "INSERT INTO cloud_memory_sync_metadata (memoryId, revision, lastSyncedRevision) " +
            "VALUES ('mem-v7-1', 1, 0)"
        )
        val metaCursor = db.query("SELECT memoryId, revision FROM cloud_memory_sync_metadata WHERE memoryId = 'mem-v7-1'")
        assertTrue("cloud_memory_sync_metadata table must be created and writable", metaCursor.moveToFirst())
        assertEquals("mem-v7-1", metaCursor.getString(0))
        assertEquals(1L, metaCursor.getLong(1))
        metaCursor.close()

        // Validate new table cloud_sync_outbox is operational
        db.execSQL(
            "INSERT INTO cloud_sync_outbox (id, memoryId, revision, operation, createdAt, attemptCount) " +
            "VALUES ('ob-1', 'mem-v7-1', 1, 'UPSERT', 1000, 0)"
        )
        val outboxCursor = db.query("SELECT id, operation, revision FROM cloud_sync_outbox WHERE id = 'ob-1'")
        assertTrue("cloud_sync_outbox table must be created and writable", outboxCursor.moveToFirst())
        assertEquals("ob-1", outboxCursor.getString(0))
        assertEquals("UPSERT", outboxCursor.getString(1))
        assertEquals(1L, outboxCursor.getLong(2))
        outboxCursor.close()
    }
}
