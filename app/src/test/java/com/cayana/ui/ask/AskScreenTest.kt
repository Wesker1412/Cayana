package com.cayana.ui.ask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.llm.LlmProviderInfo
import com.cayana.ask.security.DefaultInteractiveAuthorizationGate
import com.cayana.ask.service.DefaultAskService
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.FakeMemoryRepository
import com.cayana.retrieval.MemoryContext
import com.cayana.retrieval.MemoryRetriever
import com.cayana.retrieval.RetrievalConfidence
import com.cayana.retrieval.RetrievalQuery
import com.cayana.retrieval.RetrievalResult
import com.cayana.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskScreenTest {

    private val composeTestRule = createComposeRule()

    private val registerActivityRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val shadowPm = Shadows.shadowOf(context.packageManager)
                val component = ComponentName(context.packageName, "androidx.activity.ComponentActivity")
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                shadowPm.addActivityIfNotPresent(component)
                shadowPm.addIntentFilterForActivity(component, filter)
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeTestRule)

    private lateinit var memoryRepository: FakeMemoryRepository
    private lateinit var gate: DefaultInteractiveAuthorizationGate
    private lateinit var localFakeLlm: FakeLlmProvider
    private lateinit var remoteFakeLlm: FakeLlmProvider
    private lateinit var viewModel: AskViewModel

    private val sampleMemory = MemoryContext(
        memoryId = "mem-test-42",
        sourceType = SourceType.SCREENSHOT,
        capturedAt = 1728000000000L,
        title = "Ticket Info",
        relevantExcerpt = "Ticket for evening train to Hualien.",
        relevanceScore = 0.95
    )

    private val fakeRetriever = object : MemoryRetriever {
        override suspend fun retrieve(query: RetrievalQuery): RetrievalResult {
            return RetrievalResult(query = query.text, items = listOf(sampleMemory), confidence = RetrievalConfidence.HIGH)
        }
    }

    @Before
    fun setup() {
        memoryRepository = FakeMemoryRepository()
        runBlocking {
            memoryRepository.saveMemory(
                MemoryItem(
                    id = "mem-test-42",
                    sourceType = SourceType.SCREENSHOT,
                    title = "Ticket Info",
                    rawText = "Ticket for evening train to Hualien."
                ),
                com.cayana.memory.repository.MutationOrigin.LOCAL
            )
        }

        gate = DefaultInteractiveAuthorizationGate()
        localFakeLlm = FakeLlmProvider(
            info = LlmProviderInfo("local", "Local Provider", isLocal = true, "local-v1")
        )
        remoteFakeLlm = FakeLlmProvider(
            info = LlmProviderInfo("remote", "Cayana Remote LLM", isLocal = false, "remote-v1")
        )

        val localService = DefaultAskService(fakeRetriever, ContextPackBuilder(), localFakeLlm, gate)
        val remoteService = DefaultAskService(fakeRetriever, ContextPackBuilder(), remoteFakeLlm, gate)

        viewModel = AskViewModel(
            localAskService = localService,
            remoteAskService = remoteService,
            authorizationGate = gate,
            memoryRepository = memoryRepository
        )
    }

    @Test
    fun `askScreen renders title input box and placeholder`() {
        composeTestRule.setContent {
            AskScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithText("問問 Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("詢問任何關於你記憶的問題……").assertIsDisplayed()
        composeTestRule.onNodeWithText("示範模式").assertIsDisplayed()
    }

    @Test
    fun `asking question in local mode displays answer and source card`() {
        composeTestRule.setContent {
            AskScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithText("詢問任何關於你記憶的問題……").performTextInput("Where is ticket?")
        composeTestRule.onNodeWithContentDescription("提問").performClick()

        // Wait for coroutines to complete
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("回答").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ticket Info").assertIsDisplayed()
    }

    @Test
    fun `asking question in remote mode pops privacy preview dialog with confirm and cancel`() {
        composeTestRule.setContent {
            AskScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        // Toggle to remote mode
        composeTestRule.onNodeWithText("示範模式").performClick()
        composeTestRule.onNodeWithText("遠端模式").assertIsDisplayed()

        composeTestRule.onNodeWithText("詢問任何關於你記憶的問題……").performTextInput("Where is ticket?")
        composeTestRule.onNodeWithContentDescription("提問").performClick()

        composeTestRule.waitForIdle()

        // Privacy Authorization Dialog must be visible
        composeTestRule.onNodeWithText("遠端傳輸隱私預覽").assertIsDisplayed()
        composeTestRule.onNodeWithText("目標服務：Cayana Remote LLM (remote-v1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("傳送問題：").assertIsDisplayed()
        composeTestRule.onNodeWithText("同意並傳送").assertIsDisplayed()
        composeTestRule.onNodeWithText("取消").assertIsDisplayed()

        // Click cancel
        composeTestRule.onNodeWithText("取消").performClick()
        composeTestRule.waitForIdle()

        // Verify cancelled feedback is shown
        composeTestRule.onNodeWithText("已取消遠端傳輸，未送出任何資料。").assertIsDisplayed()
    }
}
