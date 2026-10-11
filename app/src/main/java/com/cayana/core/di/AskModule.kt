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
import com.cayana.ask.security.DefaultInteractiveAuthorizationGate
import com.cayana.ask.security.InteractiveAuthorizationGate
import com.cayana.ask.security.PermissiveAuthorizationGate
import com.cayana.ask.service.AskService
import com.cayana.ask.service.DefaultAskService
import org.koin.core.qualifier.named
import org.koin.dsl.module

val askModule = module {
    single { ContextPackBuilder() }

    single<InteractiveAuthorizationGate> {
        DefaultInteractiveAuthorizationGate(fallbackLocalGate = PermissiveAuthorizationGate())
    }

    single<AskAuthorizationGate> {
        get<InteractiveAuthorizationGate>()
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

    // Local offline FakeLlmProvider
    single {
        FakeLlmProvider()
    }

    single<LlmProvider> {
        get<FakeLlmProvider>()
    }

    single<AskService>(named("localAskService")) {
        DefaultAskService(
            memoryRetriever = get(),
            contextPackBuilder = get(),
            llmProvider = get<FakeLlmProvider>(),
            authorizationGate = get<InteractiveAuthorizationGate>()
        )
    }

    single<AskService>(named("remoteAskService")) {
        DefaultAskService(
            memoryRetriever = get(),
            contextPackBuilder = get(),
            llmProvider = get<CayanaRemoteLlmProvider>(),
            authorizationGate = get<InteractiveAuthorizationGate>()
        )
    }

    single<AskService> {
        get(named("localAskService"))
    }
}
