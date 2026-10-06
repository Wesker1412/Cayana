package com.cayana.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.MemoryRepository
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.search.data.SearchIndexStateDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchIndexRepairWorkerTest {

    private lateinit var context: Context
    private lateinit var database: CayanaDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            CayanaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        database.close()
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
    }

    @Test
    fun repairWorkerFailureReturnsRetry() = runBlocking {
        database.searchIndexStateDao().markDirty()
        assertTrue(database.searchIndexStateDao().isDirty() == true)

        val realRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        var shouldFail = true
        val testRepo = object : MemoryRepository by realRepo {
            override suspend fun rebuildSearchIndex() {
                if (shouldFail) {
                    throw RuntimeException("Simulated rebuild search index disk failure")
                }
                realRepo.rebuildSearchIndex()
            }
        }

        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            androidContext(context)
            modules(module {
                single<MemoryRepository> { testRepo }
                single<SearchIndexStateDao> { database.searchIndexStateDao() }
            })
        }

        // Run worker while rebuild fails
        val worker1 = TestListenableWorkerBuilder<SearchIndexRepairWorker>(context).build()
        val result1 = worker1.doWork()

        assertEquals(ListenableWorker.Result.retry(), result1)
        assertTrue("Index must remain dirty when rebuild throws", database.searchIndexStateDao().isDirty() == true)

        // Now allow rebuild to succeed
        shouldFail = false
        val worker2 = TestListenableWorkerBuilder<SearchIndexRepairWorker>(context).build()
        val result2 = worker2.doWork()

        assertEquals(ListenableWorker.Result.success(), result2)
        assertFalse("Index must be cleared of dirty flag on successful repair", database.searchIndexStateDao().isDirty() == true)
    }

    @Test
    fun repairWorkerWhenNotDirtyReturnsSuccessImmediately() = runBlocking {
        val realRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            androidContext(context)
            modules(module {
                single<MemoryRepository> { realRepo }
                single<SearchIndexStateDao> { database.searchIndexStateDao() }
            })
        }

        val worker = TestListenableWorkerBuilder<SearchIndexRepairWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }
}
