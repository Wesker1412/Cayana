package com.cayana.ask

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.ask.context.ContextPackBuilder
import com.cayana.ask.llm.FakeLlmProvider
import com.cayana.ask.model.AskResult
import com.cayana.ask.security.PermissiveAuthorizationGate
import com.cayana.ask.service.DefaultAskService
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.retrieval.DefaultMemoryRetriever
import com.cayana.source.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadOnlyDatabaseSafetyTest {

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var retriever: DefaultMemoryRetriever
    private lateinit var fakeLlm: FakeLlmProvider
    private lateinit var askService: DefaultAskService

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao(),
            database = database
        )

        retriever = DefaultMemoryRetriever(
            memoryRepository = repository,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        fakeLlm = FakeLlmProvider()

        askService = DefaultAskService(
            memoryRetriever = retriever,
            contextPackBuilder = ContextPackBuilder(),
            llmProvider = fakeLlm,
            authorizationGate = PermissiveAuthorizationGate()
        )

        runBlocking {
            // Seed 3 initial memories
            for (i in 1..3) {
                repository.saveMemory(
                    MemoryItem(
                        id = "seed-$i",
                        sourceType = SourceType.SCREENSHOT,
                        createdAt = 1000L * i,
                        capturedAt = 1000L * i,
                        title = "Memory Title $i",
                        rawText = "Original content of record $i",
                        processingState = ProcessingState.COMPLETED
                    )
                )
            }
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `database remains 100 percent unmodified when llm provider throws exception`() = runBlocking {
        val initialEntities = database.memoryDao().getAllMemoriesDirect()
        assertEquals(3, initialEntities.size)

        fakeLlm.throwException = RuntimeException("Simulated cloud LLM socket error or quota exceeded")

        val result = askService.ask("Memory Title 1")
        assertTrue("Expected error result", result is AskResult.Error)

        val postEntities = database.memoryDao().getAllMemoriesDirect()
        assertEquals(3, postEntities.size)
        assertEquals(initialEntities.map { it.id to it.rawText }, postEntities.map { it.id to it.rawText })
    }

    @Test
    fun `database remains 100 percent unmodified when query coroutine is cancelled`() = runBlocking {
        val initialEntities = database.memoryDao().getAllMemoriesDirect()
        assertEquals(3, initialEntities.size)

        fakeLlm.simulateDelayMs = 2000L

        val job = async(Dispatchers.IO) {
            askService.ask("Memory Title 2")
        }

        // Cancel job while in flight
        kotlinx.coroutines.delay(100)
        job.cancelAndJoin()

        val postEntities = database.memoryDao().getAllMemoriesDirect()
        assertEquals(3, postEntities.size)
        assertEquals(initialEntities.map { it.id to it.rawText }, postEntities.map { it.id to it.rawText })
    }

    @Test
    fun `database remains unmodified when memory contains malicious sql injection payloads`() = runBlocking {
        // Add a memory with prompt injection SQL payload
        repository.saveMemory(
            MemoryItem(
                id = "sql-injection-1",
                sourceType = SourceType.SCREENSHOT,
                createdAt = 5000L,
                capturedAt = 5000L,
                title = "SQL Injection Payload",
                rawText = "'; DROP TABLE memories; DELETE FROM memories; --",
                processingState = ProcessingState.COMPLETED
            )
        )

        val countBefore = database.memoryDao().getAllMemoriesDirect().size
        assertEquals(4, countBefore)

        val result = askService.ask("SQL Injection")
        assertTrue(result is AskResult.Success)

        val countAfter = database.memoryDao().getAllMemoriesDirect().size
        assertEquals(4, countAfter)
    }
}
