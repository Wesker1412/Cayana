package com.cayana.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cayana.calendar.AndroidCalendarProviderHelper
import com.cayana.calendar.CalendarProcessingCoordinator
import com.cayana.calendar.CalendarProviderHelper
import com.cayana.calendar.writer.AndroidCalendarWriter
import com.cayana.calendar.writer.CalendarWriter
import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.DefaultCayanaLogger
import com.cayana.core.permission.AndroidPermissionChecker
import com.cayana.core.permission.PermissionChecker
import com.cayana.event.extractor.DeterministicEventExtractor
import com.cayana.event.extractor.EventExtractor
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

    single<EventExtractor> {
        DeterministicEventExtractor()
    }

    single<CalendarWriter> {
        AndroidCalendarWriter(
            context = androidContext(),
            calendarProviderHelper = get(),
            logger = get()
        )
    }

    single {
        CalendarProcessingCoordinator(
            context = androidContext(),
            eventExtractor = get(),
            calendarWriter = get(),
            calendarProviderHelper = get(),
            calendarActionDao = get(),
            settingsRepository = get(),
            logger = get()
        )
    }

    single<com.cayana.processing.OcrEngine> {
        com.cayana.processing.ocr.MlKitOcrEngine(
            context = androidContext(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.screenshot.ScreenshotProcessingCoordinator(
            context = androidContext(),
            memoryRepository = get(),
            settingsRepository = get(),
            permissionChecker = get(),
            ocrEngine = get(),
            dispatchers = get(),
            calendarProcessingCoordinator = get()
        )
    }

    single {
        com.cayana.source.screenshot.ScreenshotSourceWatcher(
            context = androidContext(),
            permissionChecker = get(),
            coordinator = get(),
            settingsRepository = get(),
            dispatchers = get()
        )
    }

    single<com.cayana.processing.SpeechToTextEngine> {
        com.cayana.processing.stt.SherpaOnnxSttEngine(androidContext())
    }

    single {
        com.cayana.processing.stt.SherpaModelInstaller(
            context = androidContext(),
            settingsRepository = get(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.photo.PhotoProcessingCoordinator(
            context = androidContext(),
            memoryRepository = get(),
            settingsRepository = get(),
            permissionChecker = get(),
            ocrEngine = get(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.photo.PhotoSourceWatcher(
            context = androidContext(),
            permissionChecker = get(),
            coordinator = get(),
            settingsRepository = get(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.recording.RecordingProcessingCoordinator(
            context = androidContext(),
            memoryRepository = get(),
            settingsRepository = get(),
            permissionChecker = get(),
            sttEngine = get(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.recording.RecordingSourceWatcher(
            context = androidContext(),
            permissionChecker = get(),
            coordinator = get(),
            settingsRepository = get(),
            dispatchers = get()
        )
    }

    single {
        com.cayana.source.SourceExistenceValidator(
            context = androidContext(),
            permissionChecker = get()
        )
    }
}
