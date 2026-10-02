package com.cayana.memory.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CayanaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        const val DATABASE_NAME = "cayana_memory.db"
    }
}
