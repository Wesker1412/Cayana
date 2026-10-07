package com.cayana.core.di

import androidx.room.Room
import com.cayana.memory.data.CayanaDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single<CayanaDatabase> {
        Room.databaseBuilder(
            androidContext(),
            CayanaDatabase::class.java,
            CayanaDatabase.DATABASE_NAME
        )
            .addMigrations(
                CayanaDatabase.MIGRATION_1_2,
                CayanaDatabase.MIGRATION_2_3,
                CayanaDatabase.MIGRATION_3_4,
                CayanaDatabase.MIGRATION_4_5,
                CayanaDatabase.MIGRATION_5_6,
                CayanaDatabase.MIGRATION_6_7,
                CayanaDatabase.MIGRATION_7_8
            )
            .build()
    }

    single { get<CayanaDatabase>().memoryDao() }
    single { get<CayanaDatabase>().calendarActionDao() }
    single { get<CayanaDatabase>().searchDao() }
    single { get<CayanaDatabase>().shareReceiptDao() }
    single { get<CayanaDatabase>().searchIndexStateDao() }
    single { get<CayanaDatabase>().restoredCalendarActionHistoryDao() }
    single { get<CayanaDatabase>().cloudSyncStateDao() }
    single { get<CayanaDatabase>().cloudMemorySyncMetadataDao() }
    single { get<CayanaDatabase>().cloudSyncOutboxDao() }
}
