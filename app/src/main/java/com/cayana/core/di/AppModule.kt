package com.cayana.core.di

import com.cayana.core.common.AppDispatchers
import com.cayana.core.common.CoroutineDispatchers
import com.cayana.core.logging.CayanaLogger
import com.cayana.core.logging.DefaultCayanaLogger
import org.koin.dsl.module

val appModule = module {
    single<CoroutineDispatchers> { AppDispatchers() }
    single<CayanaLogger> { DefaultCayanaLogger() }
}
