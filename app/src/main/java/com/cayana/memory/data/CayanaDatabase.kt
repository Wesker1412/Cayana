package com.cayana.memory.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.search.data.SearchDao
import com.cayana.search.data.SearchIndexStateDao
import com.cayana.search.data.SearchIndexStateEntity
import com.cayana.source.share.data.ShareReceiptDao
import com.cayana.source.share.data.ShareReceiptEntity

@Database(
    entities = [
        MemoryEntity::class,
        CalendarActionEntity::class,
        MemoryFtsEntity::class,
        ShareReceiptEntity::class,
        SearchIndexStateEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class CayanaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun calendarActionDao(): CalendarActionDao
    abstract fun searchDao(): SearchDao
    abstract fun shareReceiptDao(): ShareReceiptDao
    abstract fun searchIndexStateDao(): SearchIndexStateDao

    companion object {
        const val DATABASE_NAME = "cayana_memory.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_sourceUri` ON `memories` (`sourceUri`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `calendar_actions` (
                        `id` TEXT NOT NULL,
                        `memoryId` TEXT NOT NULL,
                        `calendarId` INTEGER NOT NULL,
                        `calendarEventId` INTEGER,
                        `actionType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `title` TEXT,
                        `startAt` INTEGER,
                        `endAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_actions_memoryId_actionType` ON `calendar_actions` (`memoryId`, `actionType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_actions_memoryId` ON `calendar_actions` (`memoryId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `calendar_actions` ADD COLUMN `location` TEXT")
                db.execSQL("ALTER TABLE `calendar_actions` ADD COLUMN `isAllDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `calendar_actions` ADD COLUMN `zoneId` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts` USING FTS4(
                        `memoryId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `rawText` TEXT NOT NULL,
                        `normalizedText` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `searchTokens` TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                val cursor = db.query("SELECT id, sourceType, capturedAt, title, rawText, normalizedText, sourceUrl, metadataJson FROM memories")
                val insertStmt = db.compileStatement(
                    """
                    INSERT INTO `memories_fts` (
                        `memoryId`, `title`, `rawText`, `normalizedText`,
                        `sourceType`, `sourceUrl`, `host`, `displayName`, `searchTokens`
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                )

                try {
                    val idCol = cursor.getColumnIndex("id")
                    val sourceCol = cursor.getColumnIndex("sourceType")
                    val capturedCol = cursor.getColumnIndex("capturedAt")
                    val titleCol = cursor.getColumnIndex("title")
                    val rawCol = cursor.getColumnIndex("rawText")
                    val normCol = cursor.getColumnIndex("normalizedText")
                    val urlCol = cursor.getColumnIndex("sourceUrl")
                    val metaCol = cursor.getColumnIndex("metadataJson")

                    while (cursor.moveToNext()) {
                        val id = if (idCol >= 0) cursor.getString(idCol) ?: "" else ""
                        val sourceTypeStr = if (sourceCol >= 0) cursor.getString(sourceCol) ?: "" else ""
                        val capturedAt = if (capturedCol >= 0) cursor.getLong(capturedCol) else 0L
                        val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "" else ""
                        val rawText = if (rawCol >= 0) cursor.getString(rawCol) ?: "" else ""
                        val normText = if (normCol >= 0) cursor.getString(normCol) ?: "" else ""
                        val sourceUrl = if (urlCol >= 0) cursor.getString(urlCol) ?: "" else ""
                        val metaJson = if (metaCol >= 0) cursor.getString(metaCol) ?: "" else ""

                        val metadata = Converters.parseMetadata(metaJson)
                        val sourceType = runCatching { com.cayana.source.SourceType.valueOf(sourceTypeStr) }
                            .getOrDefault(com.cayana.source.SourceType.SCREENSHOT)
                        val host = com.cayana.search.MemorySearchDocumentBuilder.extractHost(sourceUrl) ?: ""
                        val displayName = metadata["displayName"] ?: metadata["filename"] ?: ""
                        val searchTokens = com.cayana.search.MemorySearchDocumentBuilder.buildSearchTokens(
                            title = title,
                            rawText = rawText,
                            normalizedText = normText,
                            sourceType = sourceType,
                            sourceUrl = sourceUrl,
                            host = host,
                            displayName = displayName,
                            capturedAt = capturedAt,
                            metadata = metadata
                        )

                        insertStmt.clearBindings()
                        insertStmt.bindString(1, id)
                        insertStmt.bindString(2, title)
                        insertStmt.bindString(3, rawText)
                        insertStmt.bindString(4, normText)
                        insertStmt.bindString(5, sourceTypeStr)
                        insertStmt.bindString(6, sourceUrl)
                        insertStmt.bindString(7, host)
                        insertStmt.bindString(8, displayName)
                        insertStmt.bindString(9, searchTokens)
                        insertStmt.executeInsert()
                    }
                } finally {
                    cursor.close()
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create share_receipts table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `share_receipts` (
                        `fingerprint` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `itemIdsJson` TEXT NOT NULL,
                        PRIMARY KEY(`fingerprint`)
                    )
                    """.trimIndent()
                )

                // 2. Create search_index_state table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_index_state` (
                        `id` INTEGER NOT NULL,
                        `isDirty` INTEGER NOT NULL,
                        `pendingRepairs` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT OR IGNORE INTO `search_index_state` (`id`, `isDirty`, `pendingRepairs`, `lastUpdated`) VALUES (1, 0, 0, 0)")

                // 3. Clear and cleanly rebuild memories_fts from canonical memories, collapsing stale/duplicate rows
                db.execSQL("DELETE FROM `memories_fts`")

                val cursor = db.query("SELECT id, sourceType, capturedAt, title, rawText, normalizedText, sourceUrl, metadataJson FROM memories")
                val insertStmt = db.compileStatement(
                    """
                    INSERT INTO `memories_fts` (
                        `memoryId`, `title`, `rawText`, `normalizedText`,
                        `sourceType`, `sourceUrl`, `host`, `displayName`, `searchTokens`
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                )

                try {
                    val idCol = cursor.getColumnIndex("id")
                    val sourceCol = cursor.getColumnIndex("sourceType")
                    val capturedCol = cursor.getColumnIndex("capturedAt")
                    val titleCol = cursor.getColumnIndex("title")
                    val rawCol = cursor.getColumnIndex("rawText")
                    val normCol = cursor.getColumnIndex("normalizedText")
                    val urlCol = cursor.getColumnIndex("sourceUrl")
                    val metaCol = cursor.getColumnIndex("metadataJson")

                    while (cursor.moveToNext()) {
                        val id = if (idCol >= 0) cursor.getString(idCol) ?: "" else ""
                        val sourceTypeStr = if (sourceCol >= 0) cursor.getString(sourceCol) ?: "" else ""
                        val capturedAt = if (capturedCol >= 0) cursor.getLong(capturedCol) else 0L
                        val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "" else ""
                        val rawText = if (rawCol >= 0) cursor.getString(rawCol) ?: "" else ""
                        val normText = if (normCol >= 0) cursor.getString(normCol) ?: "" else ""
                        val sourceUrl = if (urlCol >= 0) cursor.getString(urlCol) ?: "" else ""
                        val metaJson = if (metaCol >= 0) cursor.getString(metaCol) ?: "" else ""

                        val metadata = Converters.parseMetadata(metaJson)
                        val sourceType = runCatching { com.cayana.source.SourceType.valueOf(sourceTypeStr) }
                            .getOrDefault(com.cayana.source.SourceType.SCREENSHOT)
                        val host = com.cayana.search.MemorySearchDocumentBuilder.extractHost(sourceUrl) ?: ""
                        val displayName = metadata["displayName"] ?: metadata["filename"] ?: ""
                        val searchTokens = com.cayana.search.MemorySearchDocumentBuilder.buildSearchTokens(
                            title = title,
                            rawText = rawText,
                            normalizedText = normText,
                            sourceType = sourceType,
                            sourceUrl = sourceUrl,
                            host = host,
                            displayName = displayName,
                            capturedAt = capturedAt,
                            metadata = metadata
                        )

                        insertStmt.clearBindings()
                        insertStmt.bindString(1, id)
                        insertStmt.bindString(2, title)
                        insertStmt.bindString(3, rawText)
                        insertStmt.bindString(4, normText)
                        insertStmt.bindString(5, sourceTypeStr)
                        insertStmt.bindString(6, sourceUrl)
                        insertStmt.bindString(7, host)
                        insertStmt.bindString(8, displayName)
                        insertStmt.bindString(9, searchTokens)
                        insertStmt.executeInsert()
                    }
                } finally {
                    cursor.close()
                }
            }
        }
    }
}
