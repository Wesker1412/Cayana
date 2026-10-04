package com.cayana.memory.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cayana.calendar.data.CalendarActionDao
import com.cayana.calendar.data.CalendarActionEntity

@Database(
    entities = [
        MemoryEntity::class,
        CalendarActionEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class CayanaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun calendarActionDao(): CalendarActionDao

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
    }
}
