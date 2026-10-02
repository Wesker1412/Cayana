package com.cayana.memory.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MemoryEntity::class],
    version = 2,
    exportSchema = true
)
abstract class CayanaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        const val DATABASE_NAME = "cayana_memory.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_sourceUri` ON `memories` (`sourceUri`)")
            }
        }
    }
}
