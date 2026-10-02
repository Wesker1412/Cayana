package com.cayana

import android.app.Application
import com.cayana.core.di.appModule
import com.cayana.core.di.databaseModule
import com.cayana.core.di.repositoryModule
import com.cayana.core.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class CayanaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initKoin()
    }

    private fun initKoin() {
        startKoin {
            androidLogger()
            androidContext(this@CayanaApplication)
            modules(
                appModule,
                databaseModule,
                repositoryModule,
                viewModelModule
            )
        }
    }
}
