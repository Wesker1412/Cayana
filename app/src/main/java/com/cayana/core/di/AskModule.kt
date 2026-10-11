package com.cayana.core.di

import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.llm.LlmProvider
import com.cayana.ask.security.AskAuthorizationGate
import com.cayana.ask.security.PermissiveAuthorizationGate
import com.cayana.ask.service.AskService
import com.cayana.ask.service.DefaultAskService
import org.koin.dsl.module

val askModule = module {
    single { ContextPackBuilder() }

    single<AskAuthorizationGate> {
        PermissiveAuthorizationGate()
    }

    single<LlmProvider> {
        FakeLlmProvider()
    }

    single<AskService> {
        DefaultAskService(
            memoryRetriever = get(),
            contextPackBuilder = get(),
            llmProvider = get(),
            authorizationGate = get()
        )
    }
}
