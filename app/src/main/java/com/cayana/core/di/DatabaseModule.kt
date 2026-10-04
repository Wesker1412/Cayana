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
                CayanaDatabase.MIGRATION_3_4
            )
            .build()
    }

    single { get<CayanaDatabase>().memoryDao() }
    single { get<CayanaDatabase>().calendarActionDao() }
}
