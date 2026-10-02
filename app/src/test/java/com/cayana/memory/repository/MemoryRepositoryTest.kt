package com.cayana.memory.repository

import app.cash.turbine.test
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRepositoryTest {

    private lateinit var repository: MemoryRepository

    @Before
    fun setup() {
        repository = FakeMemoryRepository()
    }

    @Test
    fun `saveMemory inserts item and updates total count flow`() = runTest {
        repository.getMemoryCount().test {
            assertEquals(0, awaitItem())

            val item = MemoryItem(
                sourceType = SourceType.SCREENSHOT,
                title = "Live Concert",
                rawText = "10/18 Live Concert Taipei"
            )
            repository.saveMemory(item)

            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchMemories matches title or rawText correctly`() = runTest {
        val item1 = MemoryItem(
            sourceType = SourceType.SCREENSHOT,
            title = "Tokyo Trip itinerary",
            rawText = "Flight booking NH852"
        )
        val item2 = MemoryItem(
            sourceType = SourceType.RECORDING,
            title = "Team Meeting",
            rawText = "Discussing Q4 roadmap and Cayana architecture"
        )
        repository.saveMemory(item1)
        repository.saveMemory(item2)

        repository.searchMemories("Tokyo").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("Tokyo Trip itinerary", results.first().title)
            cancelAndIgnoreRemainingEvents()
        }

        repository.searchMemories("Cayana").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("Team Meeting", results.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markSourceExists retains memory item when source file is deleted`() = runTest {
        val item = MemoryItem(
            id = "test-item-1",
            sourceType = SourceType.SCREENSHOT,
            title = "Temporary Voucher",
            rawText = "Coupon 20% off",
            sourceExists = true
        )
        repository.saveMemory(item)

        // Simulate local source deletion
        repository.markSourceExists("test-item-1", false)

        repository.getMemoryById("test-item-1").test {
            val retrieved = awaitItem()
            assertTrue("Memory itself must not be deleted when source disappears", retrieved != null)
            assertFalse("sourceExists must be updated to false", retrieved!!.sourceExists)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
