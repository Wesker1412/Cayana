package com.cayana.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSearchTest {

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var searchEngine: DefaultMemorySearchEngine

    @Before
    fun setup() {
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
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun searchScreenshotOcr() = runBlocking {
        val memory = MemoryItem(
            id = "mem-screenshot-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Screenshot Note",
            rawText = "2026 台北馬拉松報名確認信 號碼布 12345",
            normalizedText = "2026 台北馬拉松報名確認信 號碼布 12345",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "馬拉松"))
        assertEquals(1, results.size)
        assertEquals("mem-screenshot-1", results.first().memory.id)
    }

    @Test
    fun searchPhotoOcr() = runBlocking {
        val memory = MemoryItem(
            id = "mem-photo-1",
            sourceType = SourceType.PHOTO,
            createdAt = 2000L,
            capturedAt = 2000L,
            title = "居酒屋帳單",
            rawText = "生啤酒 兩杯 烤牛舌 炸豆腐 總計 980元",
            normalizedText = "生啤酒 兩杯 烤牛舌 炸豆腐 總計 980元",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "生啤酒"))
        assertEquals(1, results.size)
        assertEquals("mem-photo-1", results.first().memory.id)
    }

    @Test
    fun searchRecordingTranscript() = runBlocking {
        val memory = MemoryItem(
            id = "mem-rec-1",
            sourceType = SourceType.RECORDING,
            createdAt = 3000L,
            capturedAt = 3000L,
            title = "語音記錄",
            rawText = "明天下午三點跟張經理核對合約細節",
            normalizedText = "明天下午三點跟張經理核對合約細節",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "張經理"))
        assertEquals(1, results.size)
        assertEquals("mem-rec-1", results.first().memory.id)
    }

    @Test
    fun searchSharedText() = runBlocking {
        val memory = MemoryItem(
            id = "mem-shared-txt-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 4000L,
            capturedAt = 4000L,
            title = "待辦事項",
            rawText = "週末記得去買皇家貓糧幼貓配方",
            normalizedText = "週末記得去買皇家貓糧幼貓配方",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "貓糧"))
        assertEquals(1, results.size)
        assertEquals("mem-shared-txt-1", results.first().memory.id)
    }

    @Test
    fun searchUrlHost() = runBlocking {
        val memory = MemoryItem(
            id = "mem-shared-url-1",
            sourceType = SourceType.SHARED_URL,
            createdAt = 5000L,
            capturedAt = 5000L,
            title = "GitHub",
            sourceUrl = "https://github.com/google/guava",
            metadata = mapOf("host" to "github.com"),
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "github"))
        assertEquals(1, results.size)
        assertEquals("mem-shared-url-1", results.first().memory.id)
    }

    @Test
    fun searchChineseSubstring() = runBlocking {
        val memory = MemoryItem(
            id = "mem-cjk-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 6000L,
            capturedAt = 6000L,
            title = "台北車站會面",
            rawText = "約在台北車站北三門碰面",
            normalizedText = "約在台北車站北三門碰面",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        // Search "台北"
        val res1 = searchEngine.search(SearchQuery(query = "台北"))
        assertEquals(1, res1.size)

        // Search "車站"
        val res2 = searchEngine.search(SearchQuery(query = "車站"))
        assertEquals(1, res2.size)

        // Search "北三門"
        val res3 = searchEngine.search(SearchQuery(query = "北三門"))
        assertEquals(1, res3.size)
    }

    @Test
    fun searchEnglishTokensAndPrefix() = runBlocking {
        val memory = MemoryItem(
            id = "mem-eng-1",
            sourceType = SourceType.SHARED_DOCUMENT,
            createdAt = 7000L,
            capturedAt = 7000L,
            title = "Privacy Policy Document",
            rawText = "Please review our comprehensive privacy and security terms",
            normalizedText = "Please review our comprehensive privacy and security terms",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        // Exact word token
        val res1 = searchEngine.search(SearchQuery(query = "security"))
        assertEquals(1, res1.size)

        // Prefix match
        val res2 = searchEngine.search(SearchQuery(query = "priv"))
        assertEquals(1, res2.size)
    }

    @Test
    fun searchMixedChineseEnglish() = runBlocking {
        val memory = MemoryItem(
            id = "mem-mixed-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 8000L,
            capturedAt = 8000L,
            title = "NVIDIA 發表會",
            rawText = "台北旗艦店展示 RTX 5090 顯卡售價與規格",
            normalizedText = "台北旗艦店展示 RTX 5090 顯卡售價與規格",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        val results = searchEngine.search(SearchQuery(query = "台北 RTX 5090"))
        assertEquals(1, results.size)
        assertEquals("mem-mixed-1", results.first().memory.id)
    }

    @Test
    fun specialCharactersDoNotCrash() = runBlocking {
        val memory = MemoryItem(
            id = "mem-safe-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 9000L,
            capturedAt = 9000L,
            title = "特殊符號測試",
            rawText = "包含 (括號) 與 \"引號\" 和 *星號 - 破折號: 冒號 ^ 符號",
            normalizedText = "包含 (括號) 與 \"引號\" 和 *星號 - 破折號: 冒號 ^ 符號",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        // Malformed / raw FTS special operators should be safely sanitized without SQL syntax error
        val malformedQuery = "\"*-(()^:~~"
        val results = searchEngine.search(SearchQuery(query = malformedQuery))
        // Should execute smoothly without throwing SQLiteException
        assertNotNull(results)
    }

    @Test
    fun deletedMemoryDisappearsFromIndex() = runBlocking {
        val memory = MemoryItem(
            id = "mem-delete-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 10000L,
            capturedAt = 10000L,
            title = "短暫記憶",
            rawText = "即將被刪除的機密資訊",
            normalizedText = "即將被刪除的機密資訊",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)

        var found = searchEngine.search(SearchQuery(query = "機密資訊"))
        assertEquals(1, found.size)

        repository.deleteMemory("mem-delete-1")

        found = searchEngine.search(SearchQuery(query = "機密資訊"))
        assertTrue("Deleted memory must not appear in search results", found.isEmpty())
    }

    @Test
    fun updatedTranscriptBecomesSearchable() = runBlocking {
        val initialMemory = MemoryItem(
            id = "mem-update-rec-1",
            sourceType = SourceType.RECORDING,
            createdAt = 11000L,
            capturedAt = 11000L,
            title = "錄音中",
            rawText = null,
            normalizedText = null,
            sourceExists = true,
            processingState = ProcessingState.PENDING
        )
        repository.saveMemory(initialMemory)

        var found = searchEngine.search(SearchQuery(query = "語音辨識完成"))
        assertTrue(found.isEmpty())

        // Background STT completes
        val updatedMemory = initialMemory.copy(
            title = "晨會記錄",
            rawText = "語音辨識完成：今天主要討論 Stage 5 實作",
            normalizedText = "語音辨識完成：今天主要討論 Stage 5 實作",
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(updatedMemory)

        found = searchEngine.search(SearchQuery(query = "語音辨識完成"))
        assertEquals(1, found.size)
        assertEquals("mem-update-rec-1", found.first().memory.id)
    }

    @Test
    fun rebuildIndexRestoresSearch() = runBlocking {
        val m1 = MemoryItem(
            id = "rebuild-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "備忘錄 1",
            rawText = "關鍵字測試目標 Alpha",
            normalizedText = "關鍵字測試目標 Alpha",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(m1)

        // Clear FTS index directly to simulate index corruption/loss
        database.searchDao().clearFts()
        assertEquals(0, database.searchDao().getFtsCount())

        // Rebuild index from repository
        repository.rebuildSearchIndex()

        val results = searchEngine.search(SearchQuery(query = "Alpha"))
        assertEquals(1, results.size)
        assertEquals("rebuild-1", results.first().memory.id)
    }

    @Test
    fun categoryFilterFiltersResults() = runBlocking {
        val screenshot = MemoryItem(
            id = "cat-screen",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "專案報告",
            rawText = "Cayana 設計架構截圖",
            normalizedText = "Cayana 設計架構截圖",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        val sharedText = MemoryItem(
            id = "cat-shared",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 2000L,
            capturedAt = 2000L,
            title = "專案筆記",
            rawText = "Cayana 相關備忘錄",
            normalizedText = "Cayana 相關備忘錄",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(screenshot)
        repository.saveMemory(sharedText)

        // Search ALL category
        val allResults = searchEngine.search(SearchQuery(query = "Cayana", filterCategory = SearchFilterCategory.ALL))
        assertEquals(2, allResults.size)

        // Filter SCREENSHOTS
        val screenshotResults = searchEngine.search(SearchQuery(query = "Cayana", filterCategory = SearchFilterCategory.SCREENSHOTS))
        assertEquals(1, screenshotResults.size)
        assertEquals("cat-screen", screenshotResults.first().memory.id)

        // Filter SHARED
        val sharedResults = searchEngine.search(SearchQuery(query = "Cayana", filterCategory = SearchFilterCategory.SHARED))
        assertEquals(1, sharedResults.size)
        assertEquals("cat-shared", sharedResults.first().memory.id)

        // Filter RECORDINGS (none)
        val recordingResults = searchEngine.search(SearchQuery(query = "Cayana", filterCategory = SearchFilterCategory.RECORDINGS))
        assertTrue(recordingResults.isEmpty())
    }

    @Test
    fun rankingOrdersExactTitleMatchHigherThanBodyMatch() = runBlocking {
        val bodyMatch = MemoryItem(
            id = "body-match",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 5000L, // newer
            capturedAt = 5000L,
            title = "隨手記事",
            rawText = "這是一篇關於預算的長文",
            normalizedText = "這是一篇關於預算的長文",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        val titleMatch = MemoryItem(
            id = "title-match",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L, // older
            capturedAt = 1000L,
            title = "預算",
            rawText = "無其他內容",
            normalizedText = "無其他內容",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(bodyMatch)
        repository.saveMemory(titleMatch)

        val results = searchEngine.search(SearchQuery(query = "預算"))
        assertEquals(2, results.size)
        // Title match has score 100 vs body match score 40
        assertEquals("title-match", results[0].memory.id)
        assertEquals("body-match", results[1].memory.id)
    }

    @Test
    fun sameMemoryHasExactlyOneFtsDocument() = runBlocking {
        val memory = MemoryItem(
            id = "mem-single-fts",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Version 1",
            rawText = "Version 1 content",
            normalizedText = "Version 1 content",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)
        assertEquals(1, database.searchDao().getFtsCount())

        // Save updated version of the exact same memory
        val updatedMemory = memory.copy(
            title = "Version 2",
            rawText = "Version 2 content",
            normalizedText = "Version 2 content"
        )
        repository.saveMemory(updatedMemory)
        assertEquals(1, database.searchDao().getFtsCount())
    }

    @Test
    fun updatedMemoryOldTextNoLongerSearchable() = runBlocking {
        val memory = MemoryItem(
            id = "mem-old-text-test",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "OldSecretKeyword Title",
            rawText = "OldSecretKeyword Body",
            normalizedText = "OldSecretKeyword Body",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)
        var results = searchEngine.search(SearchQuery(query = "OldSecretKeyword"))
        assertEquals(1, results.size)

        // Update with new content
        val updated = memory.copy(
            title = "BrandNewTopic Title",
            rawText = "BrandNewTopic Body",
            normalizedText = "BrandNewTopic Body"
        )
        repository.saveMemory(updated)

        // Old keyword should return 0 results
        results = searchEngine.search(SearchQuery(query = "OldSecretKeyword"))
        assertEquals(0, results.size)

        // New keyword should return 1 result
        results = searchEngine.search(SearchQuery(query = "BrandNewTopic"))
        assertEquals(1, results.size)
    }

    @Test
    fun updatedTranscriptOldTranscriptNoLongerSearchable() = runBlocking {
        val memory = MemoryItem(
            id = "mem-recording-transcript-test",
            sourceType = SourceType.RECORDING,
            createdAt = 2000L,
            capturedAt = 2000L,
            title = "Voice Memo",
            rawText = "OriginalTranscript Alpha",
            normalizedText = "OriginalTranscript Alpha",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)
        var results = searchEngine.search(SearchQuery(query = "OriginalTranscript"))
        assertEquals(1, results.size)

        // Update transcript
        val updated = memory.copy(
            rawText = "UpdatedTranscript Beta",
            normalizedText = "UpdatedTranscript Beta"
        )
        repository.saveMemory(updated)

        results = searchEngine.search(SearchQuery(query = "OriginalTranscript"))
        assertEquals(0, results.size)

        results = searchEngine.search(SearchQuery(query = "UpdatedTranscript"))
        assertEquals(1, results.size)
    }

    @Test
    fun indexDirtySurvivesRepositoryRecreation() = runBlocking {
        // Mark dirty in database
        database.searchIndexStateDao().markDirty()

        // Verify old repo knows it's dirty
        assertTrue(repository.isIndexRebuildNeeded())

        // Simulate process death / new repository instance with same database
        val newRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // New repo must also recognize dirty flag survived process death
        assertTrue(newRepo.isIndexRebuildNeeded())
    }

    @Test
    fun indexRepairAfterProcessDeathRestoresUrlSearch() = runBlocking {
        // Insert memory directly into canonical memory table, but NOT into FTS
        val memory = MemoryItem(
            id = "url-mem-crash",
            sourceType = SourceType.SHARED_URL,
            createdAt = 3000L,
            capturedAt = 3000L,
            title = "Kotlin Docs",
            rawText = "https://kotlinlang.org/docs/home.html",
            normalizedText = "https://kotlinlang.org/docs/home.html",
            sourceUrl = "https://kotlinlang.org/docs/home.html",
            sourceExists = true,
            metadata = mapOf("host" to "kotlinlang.org", "canonicalUrl" to "https://kotlinlang.org/docs/home.html"),
            processingState = ProcessingState.COMPLETED
        )
        database.memoryDao().insertOrUpdate(
            com.cayana.memory.data.MemoryEntity(
                id = memory.id,
                sourceType = memory.sourceType.name,
                createdAt = memory.createdAt,
                capturedAt = memory.capturedAt,
                title = memory.title,
                rawText = memory.rawText,
                normalizedText = memory.normalizedText,
                sourceUri = memory.sourceUri,
                sourceUrl = memory.sourceUrl,
                sourceExists = memory.sourceExists,
                metadataJson = com.cayana.memory.data.Converters.serializeMetadata(memory.metadata),
                entitiesJson = "[]",
                eventCandidatesJson = "[]",
                processingState = memory.processingState.name
            )
        )
        database.searchIndexStateDao().markDirty()

        val freshSearchEngine = DefaultMemorySearchEngine(
            memoryRepository = repository,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // Before repair, search shouldn't find it
        assertEquals(0, database.searchDao().getFtsCount())

        // Rebuild / repair index
        repository.rebuildSearchIndex()

        // Verify index is no longer dirty and search succeeds
        assertFalse(repository.isIndexRebuildNeeded())
        val searchResults = freshSearchEngine.search(SearchQuery(query = "kotlinlang"))
        assertEquals(1, searchResults.size)
        assertEquals("url-mem-crash", searchResults.first().memory.id)
    }

    @Test
    fun dirtyOrderingSaveFaultInjectionSurvivesProcessDeathAndRepairs() = runBlocking {
        var failFtsReplace = true
        val realSearchDao = database.searchDao()
        val faultInjectingSearchDao = object : com.cayana.search.data.SearchDao by realSearchDao {
            override suspend fun replaceFts(document: com.cayana.memory.data.MemoryFtsEntity) {
                if (failFtsReplace) {
                    throw RuntimeException("Simulated crash right before FTS replace")
                }
                realSearchDao.replaceFts(document)
            }
        }

        val faultRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = faultInjectingSearchDao,
            searchIndexStateDao = database.searchIndexStateDao()
        )

        val memory = MemoryItem(
            id = "mem-save-fault-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Fault Injection Note",
            rawText = "台北馬拉松號碼布 88888",
            normalizedText = "台北馬拉松號碼布 88888",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )

        // Save memory with fault injection
        faultRepo.saveMemory(memory)

        // Verify:
        // 1. markDirty succeeded and remains true
        assertTrue("Dirty flag must be set prior to canonical mutation", database.searchIndexStateDao().isDirty() == true)
        // 2. Canonical memory write succeeded
        val canonical = database.memoryDao().getMemoryById("mem-save-fault-1")
        assertNotNull("Canonical memory must be persisted", canonical)
        // 3. FTS was NOT updated due to simulated crash
        assertEquals(0, database.searchDao().getFtsCount())

        // Simulate process death / repository recreation
        val recreatedRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        val freshSearchEngine = DefaultMemorySearchEngine(
            memoryRepository = recreatedRepo,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // Dirty flag survives process death
        assertTrue("Recreated repo detects index rebuild needed", recreatedRepo.isIndexRebuildNeeded())

        // Before repair, FTS index has 0 documents
        assertEquals(0, database.searchDao().getFtsCount())

        // Perform repair
        recreatedRepo.rebuildSearchIndex()

        // After repair, dirty is false and canonical content is searchable
        assertFalse(recreatedRepo.isIndexRebuildNeeded())
        val results = freshSearchEngine.search(SearchQuery(query = "88888"))
        assertEquals(1, results.size)
        assertEquals("mem-save-fault-1", results.first().memory.id)
    }

    @Test
    fun dirtyOrderingDeleteFaultInjectionSurvivesProcessDeathAndRepairs() = runBlocking {
        // Step 1: Save memory cleanly
        val memory = MemoryItem(
            id = "mem-delete-fault-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Delete Note",
            rawText = "準備刪除的秘密資料 99999",
            normalizedText = "準備刪除的秘密資料 99999",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memory)
        var results = searchEngine.search(SearchQuery(query = "99999"))
        assertEquals(1, results.size)

        // Step 2: Fault inject searchDao.deleteFtsByMemoryId to simulate death before FTS delete
        var failFtsDelete = true
        val realSearchDao = database.searchDao()
        val faultInjectingSearchDao = object : com.cayana.search.data.SearchDao by realSearchDao {
            override suspend fun deleteFtsByMemoryId(memoryId: String) {
                if (failFtsDelete) {
                    throw RuntimeException("Simulated crash right before FTS delete")
                }
                realSearchDao.deleteFtsByMemoryId(memoryId)
            }
        }

        val faultRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = faultInjectingSearchDao,
            searchIndexStateDao = database.searchIndexStateDao()
        )

        faultRepo.deleteMemory(memory.id)

        // Verify:
        // 1. markDirty succeeded and remains true
        assertTrue("Dirty flag must be set prior to canonical deletion", database.searchIndexStateDao().isDirty() == true)
        // 2. Canonical deletion succeeded
        val canonical = database.memoryDao().getMemoryById("mem-delete-fault-1")
        assertEquals(null, canonical)
        // 3. FTS still has stale entry due to crash
        assertEquals(1, database.searchDao().getFtsCount())

        // Simulate process death / repository recreation
        val recreatedRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        val freshSearchEngine = DefaultMemorySearchEngine(
            memoryRepository = recreatedRepo,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // Dirty flag survives process death
        assertTrue("Recreated repo detects index rebuild needed", recreatedRepo.isIndexRebuildNeeded())

        // Perform repair
        recreatedRepo.rebuildSearchIndex()

        // After repair, dirty is cleared and deleted content is gone from FTS
        assertFalse(recreatedRepo.isIndexRebuildNeeded())
        results = freshSearchEngine.search(SearchQuery(query = "99999"))
        assertEquals(0, results.size)
        assertEquals(0, database.searchDao().getFtsCount())
    }

    @Test
    fun rebuildVsConcurrentSaveKeepsOnlyNewestDocument() = runBlocking {
        // Step 1: Save old text
        val memoryA = MemoryItem(
            id = "mem-concurrent-save-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Document A Old",
            rawText = "old unique text alpha",
            normalizedText = "old unique text alpha",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memoryA)

        // Step 2: Use custom MemoryDao to pause rebuild after it starts/reads snapshot
        val rebuildStarted = CompletableDeferred<Unit>()
        val canProceedRebuild = CompletableDeferred<Unit>()

        val realMemoryDao = database.memoryDao()
        val interceptedMemoryDao = object : com.cayana.memory.data.MemoryDao by realMemoryDao {
            override suspend fun getAllMemoriesDirect(): List<com.cayana.memory.data.MemoryEntity> {
                // Rebuild is inside searchIndexMutationMutex, reading snapshot
                val result = realMemoryDao.getAllMemoriesDirect()
                rebuildStarted.complete(Unit)
                canProceedRebuild.await()
                return result
            }
        }

        val concurrentRepo = RoomMemoryRepository(
            memoryDao = interceptedMemoryDao,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        val concurrentSearchEngine = DefaultMemorySearchEngine(
            memoryRepository = concurrentRepo,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // Launch rebuild in background coroutine
        val rebuildJob = launch(Dispatchers.IO) {
            concurrentRepo.rebuildSearchIndex()
        }

        // Wait until rebuild starts and acquires lock
        rebuildStarted.await()

        // Launch concurrent save of "new text"
        val updatedMemoryA = memoryA.copy(
            title = "Document A New",
            rawText = "new unique text beta",
            normalizedText = "new unique text beta"
        )
        val saveJob = launch(Dispatchers.IO) {
            concurrentRepo.saveMemory(updatedMemoryA)
        }

        // Allow rebuild to finish its work
        canProceedRebuild.complete(Unit)

        // Await both jobs
        rebuildJob.join()
        saveJob.join()

        // Assertions:
        // 1. FTS rows for A = 1
        assertEquals(1, database.searchDao().getFtsCount())

        // 2. search "new text" -> found
        val newResults = concurrentSearchEngine.search(SearchQuery(query = "new unique text beta"))
        assertEquals(1, newResults.size)
        assertEquals("mem-concurrent-save-1", newResults[0].memory.id)

        // 3. search "old text" -> not found
        val oldResults = concurrentSearchEngine.search(SearchQuery(query = "old unique text alpha"))
        assertEquals(0, oldResults.size)

        // 4. dirty = false
        assertFalse("Search index must not be dirty", concurrentRepo.isIndexRebuildNeeded())
        assertEquals(false, database.searchIndexStateDao().isDirty())
    }

    @Test
    fun rebuildVsConcurrentDeleteDoesNotResurrectDeletedDocument() = runBlocking {
        // Step 1: Save memory A
        val memoryA = MemoryItem(
            id = "mem-concurrent-del-1",
            sourceType = SourceType.SHARED_TEXT,
            createdAt = 1000L,
            capturedAt = 1000L,
            title = "Delete Target",
            rawText = "target to delete concurrent gamma",
            normalizedText = "target to delete concurrent gamma",
            sourceExists = true,
            processingState = ProcessingState.COMPLETED
        )
        repository.saveMemory(memoryA)

        val rebuildStarted = CompletableDeferred<Unit>()
        val canProceedRebuild = CompletableDeferred<Unit>()

        val realMemoryDao = database.memoryDao()
        val interceptedMemoryDao = object : com.cayana.memory.data.MemoryDao by realMemoryDao {
            override suspend fun getAllMemoriesDirect(): List<com.cayana.memory.data.MemoryEntity> {
                val result = realMemoryDao.getAllMemoriesDirect()
                rebuildStarted.complete(Unit)
                canProceedRebuild.await()
                return result
            }
        }

        val concurrentRepo = RoomMemoryRepository(
            memoryDao = interceptedMemoryDao,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )
        val concurrentSearchEngine = DefaultMemorySearchEngine(
            memoryRepository = concurrentRepo,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao()
        )

        // Launch rebuild in background
        val rebuildJob = launch(Dispatchers.IO) {
            concurrentRepo.rebuildSearchIndex()
        }

        // Wait until rebuild starts and acquires lock
        rebuildStarted.await()

        // Launch concurrent delete while rebuild is running
        val deleteJob = launch(Dispatchers.IO) {
            concurrentRepo.deleteMemory(memoryA.id)
        }

        // Allow rebuild to proceed
        canProceedRebuild.complete(Unit)

        // Wait for both
        rebuildJob.join()
        deleteJob.join()

        // Assertions:
        // 1. canonical Memory absent
        val canonical = database.memoryDao().getMemoryById("mem-concurrent-del-1")
        assertNull("Canonical memory must be deleted", canonical)

        // 2. FTS Memory absent (not resurrected by rebuild!)
        assertEquals(0, database.searchDao().getFtsCount())
        val results = concurrentSearchEngine.search(SearchQuery(query = "target to delete concurrent gamma"))
        assertEquals(0, results.size)

        // 3. dirty = false
        assertFalse("Search index must not be dirty", concurrentRepo.isIndexRebuildNeeded())
        assertEquals(false, database.searchIndexStateDao().isDirty())
    }
}
