package com.cayana.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cayana.calendar.AndroidCalendarProviderHelper
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.core.permission.AndroidPermissionChecker
import com.cayana.core.permission.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<CoroutineDispatchers> { AppDispatchers() }
    single<CayanaLogger> { DefaultCayanaLogger() }

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { androidContext().preferencesDataStoreFile("cayana_settings") }
        )
    }

    single<PermissionChecker> {
        AndroidPermissionChecker(context = androidContext())
    }

    single<CalendarProviderHelper> {
        AndroidCalendarProviderHelper(
            context = androidContext(),
            logger = get()
        )
    }
}
