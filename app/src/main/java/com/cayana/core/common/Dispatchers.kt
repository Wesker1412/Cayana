package com.cayana.core.common

import kotlinx.coroutines.CoroutineDispatcher

interface CoroutineDispatchers {
    val io: CoroutineDispatcher
    val computation: CoroutineDispatcher
    val main: CoroutineDispatcher
}
