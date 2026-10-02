package com.cayana.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AppDispatchers(
    override val io: CoroutineDispatcher = Dispatchers.IO,
    override val computation: CoroutineDispatcher = Dispatchers.Default,
    override val main: CoroutineDispatcher = Dispatchers.Main
) : CoroutineDispatchers
