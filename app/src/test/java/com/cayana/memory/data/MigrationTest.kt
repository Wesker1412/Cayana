package com.cayana.memory.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
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
}
