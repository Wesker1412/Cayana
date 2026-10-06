package com.cayana.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.search.DefaultMemorySearchEngine
import com.cayana.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSearchUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var searchEngine: DefaultMemorySearchEngine
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        searchEngine = DefaultMemorySearchEngine(
            memoryRepository = repository,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        viewModel = HomeViewModel(
            memoryRepository = repository,
            searchEngine = searchEngine
        )

        // Seed Chinese memory
        repository.saveMemory(
            MemoryItem(
                id = "mem-chinese-1",
                sourceType = SourceType.SHARED_TEXT,
                createdAt = 1000L,
                capturedAt = 1000L,
                title = "今晚在台北車站見",
                rawText = "今晚在台北車站見",
                normalizedText = "今晚在台北車站見",
                sourceExists = true,
                processingState = ProcessingState.COMPLETED
            )
        )

        // Seed 4 distinct source memories
        repository.saveMemory(
            MemoryItem(
                id = "mem-alpha",
                sourceType = SourceType.SCREENSHOT,
                createdAt = 2000L,
                capturedAt = 2000L,
                title = "Screenshot Alpha",
                rawText = "Important information Alpha exclusively",
                normalizedText = "Important information Alpha exclusively",
                sourceExists = true,
                processingState = ProcessingState.COMPLETED
            )
        )
        repository.saveMemory(
            MemoryItem(
                id = "mem-beta",
                sourceType = SourceType.PHOTO,
                createdAt = 3000L,
                capturedAt = 3000L,
                title = "Photo Beta",
                rawText = "Receipt details Beta exclusively",
                normalizedText = "Receipt details Beta exclusively",
                sourceExists = true,
                processingState = ProcessingState.COMPLETED
            )
        )
        repository.saveMemory(
            MemoryItem(
                id = "mem-gamma",
                sourceType = SourceType.RECORDING,
                createdAt = 4000L,
                capturedAt = 4000L,
                title = "Voice Memo Gamma",
                rawText = "Audio transcript Gamma exclusively",
                normalizedText = "Audio transcript Gamma exclusively",
                sourceExists = true,
                processingState = ProcessingState.COMPLETED
            )
        )
        repository.saveMemory(
            MemoryItem(
                id = "mem-delta",
                sourceType = SourceType.SHARED_TEXT,
                createdAt = 5000L,
                capturedAt = 5000L,
                title = "Shared Delta",
                rawText = "Shared text Delta exclusively",
                normalizedText = "Shared text Delta exclusively",
                sourceExists = true,
                processingState = ProcessingState.COMPLETED
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun chineseSearchUiMatchesTaipeiAndChezhanViaTextInput() {
        composeTestRule.setContent {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = {}
            )
        }

        // Test 1: Search "台北"
        composeTestRule.onNode(hasSetTextAction()).performTextInput("台北")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("今晚在台北車站見").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Screenshot Alpha").fetchSemanticsNodes().isEmpty()
        }

        composeTestRule.onNodeWithText("今晚在台北車站見").assertIsDisplayed()
        composeTestRule.onNodeWithText("Screenshot Alpha").assertDoesNotExist()

        // Test 2: Clear and search "車站"
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("車站")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("今晚在台北車站見").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Photo Beta").fetchSemanticsNodes().isEmpty()
        }

        composeTestRule.onNodeWithText("今晚在台北車站見").assertIsDisplayed()
        composeTestRule.onNodeWithText("Photo Beta").assertDoesNotExist()
    }

    @Test
    fun crossSourceSearchUiMatchesOnlyTargetSource() {
        composeTestRule.setContent {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = {}
            )
        }

        // 1. Alpha matches Screenshot only
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Alpha")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Screenshot Alpha").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Photo Beta").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Screenshot Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("Photo Beta").assertDoesNotExist()
        composeTestRule.onNodeWithText("Voice Memo Gamma").assertDoesNotExist()
        composeTestRule.onNodeWithText("Shared Delta").assertDoesNotExist()

        // 2. Beta matches Photo only
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Beta")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Photo Beta").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Screenshot Alpha").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Screenshot Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Photo Beta").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice Memo Gamma").assertDoesNotExist()
        composeTestRule.onNodeWithText("Shared Delta").assertDoesNotExist()

        // 3. Gamma matches Recording only
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Gamma")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Voice Memo Gamma").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Screenshot Alpha").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Screenshot Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Photo Beta").assertDoesNotExist()
        composeTestRule.onNodeWithText("Voice Memo Gamma").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shared Delta").assertDoesNotExist()

        // 4. Delta matches Shared only
        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Delta")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Shared Delta").fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("Voice Memo Gamma").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Screenshot Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Photo Beta").assertDoesNotExist()
        composeTestRule.onNodeWithText("Voice Memo Gamma").assertDoesNotExist()
        composeTestRule.onNodeWithText("Shared Delta").assertIsDisplayed()
    }
}
