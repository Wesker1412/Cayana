package com.cayana.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.common.AppDispatchers
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMemoryRepositoryTest {

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            dispatchers = AppDispatchers(),
            database = database
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getMemoriesCapturedSince_queriesBoundedAndSortedByCapturedAtDesc() = runTest {
        val m1 = MemoryItem(id = "mem-1", sourceType = SourceType.SCREENSHOT, capturedAt = 1000L, rawText = "Older")
        val m2 = MemoryItem(id = "mem-2", sourceType = SourceType.SCREENSHOT, capturedAt = 2000L, rawText = "Middle")
        val m3 = MemoryItem(id = "mem-3", sourceType = SourceType.SCREENSHOT, capturedAt = 3000L, rawText = "Newest")
        val mOtherType = MemoryItem(id = "mem-4", sourceType = SourceType.RECORDING, capturedAt = 2500L, rawText = "Audio")

        repository.saveMemory(m1)
        repository.saveMemory(m2)
        repository.saveMemory(m3)
        repository.saveMemory(mOtherType)

        // Query sinceCapturedAt = 2000L
        val results = repository.getMemoriesCapturedSince(
            sourceType = SourceType.SCREENSHOT,
            sinceCapturedAt = 2000L,
            limit = 50
        )

        assertEquals("Should only return SCREENSHOT memories captured at or after 2000L", 2, results.size)
        assertEquals("First item should be newest (capturedAt DESC)", "mem-3", results[0].id)
        assertEquals("Second item should be middle", "mem-2", results[1].id)

        // Test limit
        val limited = repository.getMemoriesCapturedSince(
            sourceType = SourceType.SCREENSHOT,
            sinceCapturedAt = 1000L,
            limit = 2
        )
        assertEquals(2, limited.size)
        assertEquals("mem-3", limited[0].id)
        assertEquals("mem-2", limited[1].id)
    }
}
