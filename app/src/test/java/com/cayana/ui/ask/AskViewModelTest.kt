package com.cayana.ui.ask

import app.cash.turbine.test
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.ask.model.AskOptions
import com.cayana.ask.model.AskResult
import com.cayana.ask.model.EvidenceState
import com.cayana.ask.model.MemorySourceRef
import com.cayana.ask.remote.RemoteInferenceException
import com.cayana.ask.security.DefaultInteractiveAuthorizationGate
import com.cayana.ask.service.AskService
import com.cayana.ask.service.DefaultAskService
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.retrieval.MemoryContext
import com.cayana.retrieval.MemoryRetriever
import com.cayana.retrieval.RetrievalConfidence
import com.cayana.retrieval.RetrievalQuery
import com.cayana.retrieval.RetrievalResult
import com.cayana.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val builder = ContextPackBuilder()
    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var gate: DefaultInteractiveAuthorizationGate
    private lateinit var localFakeLlm: FakeLlmProvider
    private lateinit var remoteFakeLlm: FakeLlmProvider
    private lateinit var localService: AskService
    private lateinit var remoteService: AskService
    private lateinit var viewModel: AskViewModel

    private val sampleMemory = MemoryContext(
        memoryId = "mem-passport-001",
        sourceType = SourceType.SCREENSHOT,
        capturedAt = 1728000000000L,
        title = "Passport Location",
        relevantExcerpt = "Passport kept in bedroom top drawer.",
        relevanceScore = 0.95
    )

    private val fakeRetriever = object : MemoryRetriever {
        override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
            return if (query.text.contains("unknown", ignoreCase = true)) {
                RetrievalResult(query = query.text, items = emptyList(), confidence = RetrievalConfidence.NONE)
            } else {
                RetrievalResult(query = query.text, items = listOf(sampleMemory), confidence = RetrievalConfidence.HIGH)
            }
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        memoryRepository = FakeMemoryRepository()

        // Populate a local memory item in repository for detail inspection
        kotlinx.coroutines.runBlocking {
            memoryRepository.saveMemory(
                MemoryItem(
                    id = "mem-passport-001",
                    sourceType = SourceType.SCREENSHOT,
                    title = "Passport Location",
                    rawText = "Passport kept in bedroom top drawer.",
                    normalizedText = "Passport kept in bedroom top drawer.",
                    capturedAt = 1728000000000L
                ),
                com.cayana.memory.repository.MutationOrigin.LOCAL
            )
        }

        gate = DefaultInteractiveAuthorizationGate()

        localFakeLlm = FakeLlmProvider(
            info = LlmProviderInfo("local", "Local Provider", isLocal = true, "local-v1")
        )
        remoteFakeLlm = FakeLlmProvider(
            info = LlmProviderInfo("remote", "Cayana Remote LLM", isLocal = false, "cayana-v1")
        )

        localService = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = localFakeLlm,
            authorizationGate = gate
        )

        remoteService = DefaultAskService(
            memoryRetriever = fakeRetriever,
            contextPackBuilder = builder,
            llmProvider = remoteFakeLlm,
            authorizationGate = gate
        )

        viewModel = AskViewModel(
            localAskService = localService,
            remoteAskService = remoteService,
            authorizationGate = gate,
            memoryRepository = memoryRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState is empty and idle`() = runTest {
        viewModel.uiState.test {
            val state = expectMostRecentItem()
            assertEquals("", state.query)
            assertFalse(state.isQuerying)
            assertEquals(AskProgressStage.IDLE, state.progressStage)
            assertNull(state.result)
            assertNull(state.errorMessage)
            assertNull(state.pendingAuthorization)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty query validation displays error without querying service`() = runTest {
        viewModel.onAsk()
        val state = viewModel.uiState.value
        assertEquals("請輸入問題內容", state.errorMessage)
        assertFalse(state.isQuerying)
        assertEquals(0, localFakeLlm.requestCount)
        assertEquals(0, remoteFakeLlm.requestCount)
    }

    @Test
    fun `local mode query executes successfully without remote authorization gate suspension`() = runTest {
        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isQuerying)
        assertEquals(AskProgressStage.COMPLETED, state.progressStage)
        assertNotNull(state.result)
        assertTrue(state.result is AskResult.Success)

        val success = state.result as AskResult.Success
        assertEquals(1, success.sources.size)
        assertEquals("mem-passport-001", success.sources[0].memoryId)
        assertEquals(1, localFakeLlm.requestCount)
        assertEquals(0, remoteFakeLlm.requestCount)
    }

    @Test
    fun `remote mode query pauses for user privacy authorization and cancellation sends zero requests`() = runTest {
        viewModel.onToggleRemoteProvider(true)
        assertTrue(viewModel.uiState.value.useRemoteProvider)

        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        // Advance coroutines to reach authorization gate
        testDispatcher.scheduler.runCurrent()

        val awaitingState = viewModel.uiState.value
        assertTrue(awaitingState.isQuerying)
        assertEquals(AskProgressStage.AWAITING_AUTHORIZATION, awaitingState.progressStage)
        assertNotNull(awaitingState.pendingAuthorization)

        val pending = awaitingState.pendingAuthorization!!
        assertEquals("Where is my passport?", pending.query)
        assertEquals(1, pending.contextPack.items.size)
        assertEquals("mem-passport-001", pending.contextPack.items[0].memoryId)
        assertEquals("Cayana Remote LLM", pending.providerInfo.name)

        // User rejects authorization
        viewModel.onRejectAuthorization()
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertFalse(finalState.isQuerying)
        assertEquals(AskProgressStage.IDLE, finalState.progressStage)
        assertNull(finalState.pendingAuthorization)
        assertTrue(finalState.errorMessage?.contains("已取消") == true)
        // CRITICAL PRIVACY GUARANTEE: Zero requests sent to remote LLM provider
        assertEquals(0, remoteFakeLlm.requestCount)
    }

    @Test
    fun `remote mode query with user approval sends request and delivers answer with sources`() = runTest {
        viewModel.onToggleRemoteProvider(true)
        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        testDispatcher.scheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.pendingAuthorization)

        // User approves authorization
        viewModel.onApproveAuthorization()
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertFalse(finalState.isQuerying)
        assertEquals(AskProgressStage.COMPLETED, finalState.progressStage)
        assertNotNull(finalState.result)
        assertTrue(finalState.result is AskResult.Success)

        val success = finalState.result as AskResult.Success
        assertEquals(1, remoteFakeLlm.requestCount)
        assertEquals(1, success.sources.size)
        assertEquals("mem-passport-001", success.sources[0].memoryId)
    }

    @Test
    fun `no evidence query properly reflects NoEvidence state with zero citations`() = runTest {
        viewModel.onQueryChanged("unknown inquiry about future")
        viewModel.onAsk()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.result)
        assertTrue(state.result is AskResult.NoEvidence)
        val noEvidence = state.result as AskResult.NoEvidence
        assertTrue(noEvidence.message.contains("No relevant memory records"))
        // Zero citations
        assertEquals(0, localFakeLlm.requestCount)
    }

    @Test
    fun `insufficient evidence returned by LLM is properly presented`() = runTest {
        localFakeLlm.defaultEvidenceState = EvidenceState.INSUFFICIENT_EVIDENCE

        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val success = state.result as AskResult.Success
        assertEquals(EvidenceState.INSUFFICIENT_EVIDENCE, success.evidenceState)
        assertTrue(success.answer.contains("lack sufficient details"))
    }

    @Test
    fun `network error sets isNetworkError and onRetry re-triggers query`() = runTest {
        localFakeLlm.throwException = RemoteInferenceException.NetworkError("Failed to connect to server", IOException("Network unreachable"))

        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        testDispatcher.scheduler.advanceUntilIdle()

        val errorState = viewModel.uiState.value
        assertFalse(errorState.isQuerying)
        assertTrue(errorState.isNetworkError)
        assertTrue(errorState.errorMessage?.contains("網路連線異常") == true)

        // Clear exception and retry
        localFakeLlm.throwException = null
        viewModel.onRetry()
        testDispatcher.scheduler.advanceUntilIdle()

        val retriedState = viewModel.uiState.value
        assertFalse(retriedState.isQuerying)
        assertFalse(retriedState.isNetworkError)
        assertTrue(retriedState.result is AskResult.Success)
    }

    @Test
    fun `selecting cited source loads underlying local MemoryItem`() = runTest {
        viewModel.onSelectSourceMemory("mem-passport-001")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.selectedDetailMemory)
        assertEquals("mem-passport-001", state.selectedDetailMemory!!.id)
        assertEquals("Passport Location", state.selectedDetailMemory!!.title)

        viewModel.onDismissMemoryDetail()
        assertNull(viewModel.uiState.value.selectedDetailMemory)
    }

    @Test
    fun `repeated onAsk while query is in progress is ignored`() = runTest {
        viewModel.onQueryChanged("Where is my passport?")
        localFakeLlm.simulateDelayMs = 1000L

        viewModel.onAsk()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isQuerying)

        // Attempt second onAsk during in-flight query
        viewModel.onAsk()
        testDispatcher.scheduler.runCurrent()

        // Only 1 execution was launched
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, localFakeLlm.requestCount)
    }

    @Test
    fun `toggling provider while querying is strictly forbidden`() = runTest {
        viewModel.onQueryChanged("Where is my passport?")
        localFakeLlm.simulateDelayMs = 1000L

        viewModel.onAsk()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isQuerying)
        assertFalse(viewModel.uiState.value.useRemoteProvider)

        // Attempt toggle to remote while query is in-flight
        viewModel.onToggleRemoteProvider(true)
        assertFalse(viewModel.uiState.value.useRemoteProvider)

        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.useRemoteProvider)
    }

    @Test
    fun `query execution uses fixed provider chosen at submission moment`() = runTest {
        viewModel.onQueryChanged("Where is my passport?")
        // Start with local provider (useRemoteProvider = false)
        viewModel.onAsk()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, localFakeLlm.requestCount)
        assertEquals(0, remoteFakeLlm.requestCount)
    }

    @Test
    fun `undeployed remote provider is disabled and rejects remote ask with explanatory message`() = runTest {
        val disabledRemoteLlm = FakeLlmProvider(
            info = LlmProviderInfo("disabled_remote", "Undeployed Remote LLM", isLocal = false, "v1")
        ).apply {
            customGenerator = { throw IllegalStateException("Should not be called") }
        }
        val disabledRemoteService = object : AskService {
            override val isEnabled: Boolean = false
            override suspend fun ask(query: String, options: AskOptions): AskResult {
                return AskResult.Error(query, "遠端推論服務尚未正式部署啟用，目前無法使用。")
            }
        }

        val testViewModel = AskViewModel(
            localAskService = localService,
            remoteAskService = disabledRemoteService,
            authorizationGate = gate,
            memoryRepository = memoryRepository
        )

        assertFalse(testViewModel.uiState.value.isRemoteAvailable)

        // Attempting to toggle to disabled remote provider displays explanation
        testViewModel.onToggleRemoteProvider(true)
        assertFalse(testViewModel.uiState.value.useRemoteProvider)
        assertTrue(testViewModel.uiState.value.errorMessage?.contains("尚未正式部署啟用") == true)
    }

    @Test
    fun `onCancelQuery clears pending authorization and job without transmitting`() = runTest {
        viewModel.onToggleRemoteProvider(true)
        viewModel.onQueryChanged("Where is my passport?")
        viewModel.onAsk()

        testDispatcher.scheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.pendingAuthorization)

        // User or navigation cancels query
        viewModel.onCancelQuery()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isQuerying)
        assertNull(state.pendingAuthorization)
        assertEquals(0, remoteFakeLlm.requestCount)
    }
}
