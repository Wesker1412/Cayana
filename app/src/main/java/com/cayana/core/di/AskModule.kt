package com.cayana.core.di

import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.llm.LlmProvider
import com.cayana.ask.remote.AnonymousInstallationIdProvider
import com.cayana.ask.remote.CayanaRemoteLlmProvider
import com.cayana.ask.remote.DefaultRemoteInferenceClient
import com.cayana.ask.remote.RemoteEndpointConfig
import com.cayana.ask.remote.RemoteInferenceClient
import com.cayana.ask.remote.SharedPreferencesInstallationIdProvider
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

    // Anonymous installation credentials for remote API quota & abuse prevention
    single<AnonymousInstallationIdProvider> {
        SharedPreferencesInstallationIdProvider(get())
    }

    // Remote inference endpoint configuration
    single { RemoteEndpointConfig() }

    // Remote inference HTTP client
    single<RemoteInferenceClient> { DefaultRemoteInferenceClient() }

    // Remote LLM provider adapter
    single {
        CayanaRemoteLlmProvider(
            client = get(),
            config = get(),
            installationIdProvider = get()
        )
    }

    // Safe default: use local offline FakeLlmProvider until explicit remote authorization UI in Stage 8C
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
