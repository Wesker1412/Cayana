package com.cayana.retrieval

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cayana.core.logging.CayanaLogger
import com.cayana.memory.data.CayanaDatabase
import com.cayana.memory.model.EventCandidate
import com.cayana.memory.model.EventConfidence
import com.cayana.memory.model.MemoryItem
import com.cayana.memory.repository.RoomMemoryRepository
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRetrieverTest {

    private lateinit var database: CayanaDatabase
    private lateinit var repository: RoomMemoryRepository
    private lateinit var retriever: DefaultMemoryRetriever

    private val fixedZone = ZoneId.of("Asia/Taipei")
    // Reference instant: 2026-10-15 12:00:00 UTC
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), fixedZone)
    private val defaultOptions = RetrievalOptions(clock = fixedClock, zoneId = fixedZone)

    private val testLogMessages = mutableListOf<String>()
    private val testLogger = object : CayanaLogger {
        override fun d(tag: String, message: String) { testLogMessages.add("$tag: $message") }
        override fun i(tag: String, message: String) { testLogMessages.add("$tag: $message") }
        override fun w(tag: String, message: String, throwable: Throwable?) { testLogMessages.add("$tag: $message") }
        override fun e(tag: String, message: String, throwable: Throwable?) { testLogMessages.add("$tag: $message") }
        override fun logMemoryEvent(tag: String, eventName: String, memoryId: String, rawContent: String?) {
            testLogMessages.add("$tag: $eventName $memoryId")
        }
    }

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
            searchIndexStateDao = database.searchIndexStateDao(),
            logger = testLogger
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ------------------------------------------------------------------------
    // 1. Core Boundedness Tests
    // ------------------------------------------------------------------------

    @Test
    fun emptyQueryReturnsNoContext() = runBlocking {
        repository.saveMemory(MemoryItem(title = "Test", rawText = "Content", sourceType = SourceType.SCREENSHOT))

        val result = retriever.retrieve(RetrievalQuery("", defaultOptions))
        assertTrue(result.items.isEmpty())
        assertEquals(RetrievalConfidence.NONE, result.confidence)

        val resultWhitespace = retriever.retrieve(RetrievalQuery("   ", defaultOptions))
        assertTrue(resultWhitespace.items.isEmpty())
        assertEquals(RetrievalConfidence.NONE, resultWhitespace.confidence)
    }

    @Test
    fun genericQueryDoesNotDumpRecentDatabase() = runBlocking {
        // Populate 10 memories
        for (i in 1..10) {
            repository.saveMemory(
                MemoryItem(
                    title = "Memory $i",
                    rawText = "Important information number $i",
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        val noiseQueries = listOf("那個", "之前的", "我記得", "請問一下那個之前")
        for (noise in noiseQueries) {
            val res = retriever.retrieve(RetrievalQuery(noise, defaultOptions))
            assertTrue(
                "Noise query '$noise' must never return entire database, but got ${res.items.size} items",
                res.items.isEmpty()
            )
            assertEquals(RetrievalConfidence.NONE, res.confidence)
        }
    }

    @Test
    fun retrievalReturnsBoundedTopK() = runBlocking {
        for (i in 1..15) {
            repository.saveMemory(
                MemoryItem(
                    title = "Kotlin Guide $i",
                    rawText = "Learning Kotlin coroutines and flows in depth",
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        val query = RetrievalQuery("Kotlin", defaultOptions.copy(topK = 3))
        val result = retriever.retrieve(query)
        assertEquals(3, result.items.size)
    }

    @Test
    fun retrievalNeverReturnsMoreThanPolicyMaximum() = runBlocking {
        for (i in 1..20) {
            repository.saveMemory(
                MemoryItem(
                    title = "Android Architecture $i",
                    rawText = "Android Jetpack compose Room MVI architecture patterns",
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        // Caller attempts to ask for 100 items
        val query = RetrievalQuery("Android", defaultOptions.copy(topK = 100))
        val result = retriever.retrieve(query)

        assertTrue(
            "Result count must not exceed RetrievalPolicy.MAX_TOP_K (${RetrievalPolicy.MAX_TOP_K}), got ${result.items.size}",
            result.items.size <= RetrievalPolicy.MAX_TOP_K
        )
        assertEquals(RetrievalPolicy.MAX_TOP_K, result.items.size)
    }

    // ------------------------------------------------------------------------
    // 2. Existing Sources Verification
    // ------------------------------------------------------------------------

    @Test
    fun retrievesScreenshotMemory() = runBlocking {
        val mem = MemoryItem(
            title = "捷克布拉格城堡",
            rawText = "布拉格舊城區古老鐘樓與查理大橋美景",
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("捷克城堡", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertEquals(SourceType.SCREENSHOT, result.items[0].sourceType)
    }

    @Test
    fun retrievesPhotoMemory() = runBlocking {
        val mem = MemoryItem(
            title = "居酒屋菜單",
            rawText = "碳烤牛舌 980元 串燒拼盤 450元 生啤酒",
            sourceType = SourceType.PHOTO
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("居酒屋牛舌", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertEquals(SourceType.PHOTO, result.items[0].sourceType)
    }

    @Test
    fun retrievesRecordingMemory() = runBlocking {
        val mem = MemoryItem(
            title = "產品會議錄音",
            rawText = "週五下午兩點與張經理討論新季合約與交付時程",
            sourceType = SourceType.RECORDING
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("張經理交付時程", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertEquals(SourceType.RECORDING, result.items[0].sourceType)
    }

    @Test
    fun retrievesSharedTextMemory() = runBlocking {
        val mem = MemoryItem(
            title = "備忘清單",
            rawText = "週末記得買皇家貓糧幼貓配方三包",
            sourceType = SourceType.SHARED_TEXT
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("皇家貓糧", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertEquals(SourceType.SHARED_TEXT, result.items[0].sourceType)
    }

    @Test
    fun retrievesSharedUrlMemory() = runBlocking {
        val mem = MemoryItem(
            title = "JetBrains GitHub",
            rawText = "Kotlin programming language repository",
            sourceUrl = "https://github.com/JetBrains/kotlin",
            sourceType = SourceType.SHARED_URL
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("GitHub Kotlin", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertEquals("https://github.com/JetBrains/kotlin", result.items[0].sourceUrl)
    }

    // ------------------------------------------------------------------------
    // 3. Natural Question Noise Words & Mixed Language
    // ------------------------------------------------------------------------

    @Test
    fun questionNoiseWordsDoNotPreventRelevantRetrieval() = runBlocking {
        val mem = MemoryItem(
            title = "捷克童話屋",
            rawText = "庫倫洛夫彩色童話房子照片與明信片",
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(mem)

        // Conversational query full of stop words
        val query = RetrievalQuery("我之前截圖的那個捷克房子", defaultOptions)
        val result = retriever.retrieve(query)

        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
    }

    @Test
    fun mixedChineseEnglishQuestionRetrievesCorrectMemory() = runBlocking {
        val mem = MemoryItem(
            title = "Awesome Android",
            rawText = "Curated list of awesome Android Kotlin libraries on GitHub",
            sourceUrl = "https://github.com/JStumpp/awesome-android",
            sourceType = SourceType.SHARED_URL
        )
        repository.saveMemory(mem)

        val query = RetrievalQuery("我存過 GitHub 的東西嗎？", defaultOptions)
        val result = retriever.retrieve(query)

        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
    }

    // ------------------------------------------------------------------------
    // 4. Event / Time Hint Matching (Section 28)
    // ------------------------------------------------------------------------

    @Test
    fun eventDateRanksAboveCaptureDateForTimeQuestions() = runBlocking {
        // Memory A: Captured on 2026-09-20 (Sept), but Event is on 2026-10-18 (Oct)
        val octEventTime = Instant.parse("2026-10-18T11:30:00Z").toEpochMilli() // 19:30 Taipei
        val septCaptureTime = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli()
        val memA = MemoryItem(
            id = "mem-concert-a",
            title = "XX Live",
            rawText = "XX Live 演唱會門票 台北流行音樂中心",
            capturedAt = septCaptureTime,
            sourceType = SourceType.SCREENSHOT,
            eventCandidates = listOf(
                EventCandidate(
                    title = "XX Live 演唱會",
                    startTimestamp = octEventTime,
                    location = "台北流行音樂中心",
                    confidence = EventConfidence.HIGH
                )
            )
        )
        repository.saveMemory(memA)

        // Memory B: Captured on 2026-10-10 (Oct), but completely unrelated content
        val octCaptureTime = Instant.parse("2026-10-10T08:00:00Z").toEpochMilli()
        val memB = MemoryItem(
            id = "mem-grocery-b",
            title = "超市採買",
            rawText = "購買鮮奶、雞蛋與吐司",
            capturedAt = octCaptureTime,
            sourceType = SourceType.PHOTO
        )
        repository.saveMemory(memB)

        // User asks about an event in October
        val query = RetrievalQuery("我十月是不是有一場演唱會？", defaultOptions)
        val result = retriever.retrieve(query)

        assertFalse("Must find concert memory", result.items.isEmpty())
        assertEquals("Memory A (Concert) must rank first even though captured in September", "mem-concert-a", result.items[0].memoryId)
        assertTrue(result.items[0].matchedSignals.contains(RetrievalSignal.EVENT_DATE_MATCH))
        assertNotNull(result.items[0].eventSummary)
    }

    // ------------------------------------------------------------------------
    // 5. Entity Matching (Section 29)
    // ------------------------------------------------------------------------

    @Test
    fun entityMatchesQueryEvenWhenBodyDoesNotRepeatEntity() = runBlocking {
        val mem = MemoryItem(
            id = "mem-contract-1",
            title = "業務進度說明",
            rawText = "明天下午三點核對採購合約第十二條細節條款",
            entities = listOf("張經理", "宏達科技"),
            sourceType = SourceType.RECORDING
        )
        repository.saveMemory(mem)

        val query = RetrievalQuery("我跟張經理之前講了什麼？", defaultOptions)
        val result = retriever.retrieve(query)

        assertEquals(1, result.items.size)
        assertEquals("mem-contract-1", result.items[0].memoryId)
        assertTrue(result.items[0].matchedSignals.contains(RetrievalSignal.ENTITY_MATCH))
    }

    // ------------------------------------------------------------------------
    // 6. Context Budget Tests (Section 30)
    // ------------------------------------------------------------------------

    @Test
    fun veryLongTranscriptExcerptIsStrictlyBounded() = runBlocking {
        // Create a 50,000 char long transcript with target keyword in the middle
        val sb = StringBuilder()
        for (i in 0 until 500) {
            sb.append("這是會議前面的無關冗長發言第 $i 條內容。今天天氣很好大家辛苦了。")
        }
        sb.append("關鍵決策：決定採用智慧本地檢索系統架構。")
        for (i in 0 until 500) {
            sb.append("這是會議後面的收尾討論與茶點發言第 $i 條內容。下週同一時間再開會。")
        }
        val hugeText = sb.toString()
        assertTrue(hugeText.length > 30000)

        val mem = MemoryItem(
            title = "巨長錄音逐字稿",
            rawText = hugeText,
            sourceType = SourceType.RECORDING
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("智慧本地檢索", defaultOptions))
        assertEquals(1, result.items.size)
        val excerpt = result.items[0].relevantExcerpt

        assertTrue(
            "Excerpt length ${excerpt.length} must be <= ${RetrievalPolicy.MAX_EXCERPT_CHARS_PER_MEMORY}",
            excerpt.length <= RetrievalPolicy.MAX_EXCERPT_CHARS_PER_MEMORY
        )
        assertTrue(excerpt.contains("智慧本地檢索"))
    }

    @Test
    fun combinedContextAcrossMultipleItemsNeverExceedsTotalPolicyLimit() = runBlocking {
        // Create 20 memories with long texts (each ~2,000 chars)
        for (i in 1..20) {
            val longContent = "系統架構核心說明 $i 。" + "重點內容重複段落：機器學習、本地儲存與加密備份。".repeat(40)
            repository.saveMemory(
                MemoryItem(
                    title = "架構文件 $i",
                    rawText = longContent,
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        // Request topK = 100
        val query = RetrievalQuery("架構核心", defaultOptions.copy(topK = 100))
        val result = retriever.retrieve(query)

        assertTrue(result.items.size <= RetrievalPolicy.MAX_TOP_K)

        var totalChars = 0
        for (item in result.items) {
            totalChars += item.relevantExcerpt.length
        }

        assertTrue(
            "Total combined context $totalChars must be <= ${RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS}",
            totalChars <= RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS
        )
    }

    // ------------------------------------------------------------------------
    // 7. No DB Capability Leak (Section 31)
    // ------------------------------------------------------------------------

    @Test
    fun memoryContextAndResultDoNotExposeDatabaseOrRepositoryCapabilities() {
        val contextClass = MemoryContext::class.java
        val resultClass = RetrievalResult::class.java

        // Ensure no field is a Dao, Database, Room or Repository type
        val forbiddenTypes = listOf("Dao", "Database", "Repository", "SQLite", "Room", "SecretKey")

        for (field in contextClass.declaredFields) {
            for (forbidden in forbiddenTypes) {
                assertFalse(
                    "MemoryContext field ${field.name} (${field.type.name}) must not leak $forbidden",
                    field.type.name.contains(forbidden)
                )
            }
        }

        for (field in resultClass.declaredFields) {
            for (forbidden in forbiddenTypes) {
                assertFalse(
                    "RetrievalResult field ${field.name} (${field.type.name}) must not leak $forbidden",
                    field.type.name.contains(forbidden)
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // 8. Logging Privacy Test (Section 32)
    // ------------------------------------------------------------------------

    @Test
    fun loggerNeverOutputsPlaintextSentinelOrQueryContent() = runBlocking {
        testLogMessages.clear()
        val sentinel = "PRIVATE_CAYANA_SENTINEL_778899"
        val mem = MemoryItem(
            title = "機密備忘錄 $sentinel",
            rawText = "內部極機密密鑰資訊 $sentinel 不要外流",
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(mem)

        val query = RetrievalQuery(sentinel, defaultOptions)
        val result = retriever.retrieve(query)
        assertEquals(1, result.items.size)

        // Verify that logger recorded telemetry, but NO plaintext sentinel or query
        assertFalse("Logger must have recorded telemetry", testLogMessages.isEmpty())
        for (log in testLogMessages) {
            assertFalse(
                "Logger message leaked private sentinel: '$log'",
                log.contains(sentinel)
            )
            assertFalse(
                "Logger message leaked plaintext title: '$log'",
                log.contains("機密備忘錄")
            )
        }

        // Verify telemetry output format
        val infoLog = testLogMessages.find { it.contains("MemoryRetriever: retrieval candidates=") }
        assertNotNull("Telemetry log must be present", infoLog)
    }

    // ------------------------------------------------------------------------
    // 9. Source Deletion & Processing State Tests (Sections 21, 22)
    // ------------------------------------------------------------------------

    @Test
    fun deletedSourceMemoryStillRetrievableWithFlagPreserved() = runBlocking {
        val mem = MemoryItem(
            title = "已刪除原圖的發票",
            rawText = "星巴克咖啡發票 160元",
            sourceExists = false, // Media deleted from device
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(mem)

        val result = retriever.retrieve(RetrievalQuery("星巴克發票", defaultOptions))
        assertEquals(1, result.items.size)
        assertEquals(mem.id, result.items[0].memoryId)
        assertFalse("sourceExists flag must be false in MemoryContext", result.items[0].sourceExists)
    }

    @Test
    fun completedMemoryRanksHigherThanProcessingMemory() = runBlocking {
        val memCompleted = MemoryItem(
            id = "mem-comp",
            title = "專案架構報告",
            rawText = "專案架構設計完整規格書",
            processingState = ProcessingState.COMPLETED,
            sourceType = SourceType.SCREENSHOT
        )
        val memProcessing = MemoryItem(
            id = "mem-proc",
            title = "專案架構草稿",
            rawText = "專案架構設計完整規格書",
            processingState = ProcessingState.PROCESSING,
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(memProcessing)
        repository.saveMemory(memCompleted)

        val result = retriever.retrieve(RetrievalQuery("專案架構規格書", defaultOptions))
        assertEquals(2, result.items.size)
        assertEquals("Completed memory must rank above processing memory", "mem-comp", result.items[0].memoryId)
    }

    // ------------------------------------------------------------------------
    // 10. Automated Acceptance Dataset (Section 39)
    // ------------------------------------------------------------------------

    @Test
    fun runtimeAcceptanceDatasetAnswersAllFourNaturalQuestions() = runBlocking {
        // Memory 1: Screenshot
        val octConcertTime = Instant.parse("2026-10-18T11:30:00Z").toEpochMilli() // 19:30 Taipei
        val mem1 = MemoryItem(
            id = "mem-acc-1",
            title = "XX Live 門票",
            rawText = "XX Live\n2026/10/18 19:30\n台北流行音樂中心\n座位 A 區 12 排",
            capturedAt = Instant.parse("2026-09-15T08:00:00Z").toEpochMilli(),
            sourceType = SourceType.SCREENSHOT,
            eventCandidates = listOf(
                EventCandidate(
                    title = "XX Live",
                    startTimestamp = octConcertTime,
                    location = "台北流行音樂中心",
                    confidence = EventConfidence.HIGH
                )
            )
        )
        repository.saveMemory(mem1)

        // Memory 2: Photo
        val mem2 = MemoryItem(
            id = "mem-acc-2",
            title = "居酒屋消費明細",
            rawText = "居酒屋帳單\n烤牛舌\n980元\n服務費 10%",
            sourceType = SourceType.PHOTO
        )
        repository.saveMemory(mem2)

        // Memory 3: Recording
        val mem3 = MemoryItem(
            id = "mem-acc-3",
            title = "週五會議速記",
            rawText = "週五下午跟張經理討論新合約條款與保密協定",
            entities = listOf("張經理"),
            sourceType = SourceType.RECORDING
        )
        repository.saveMemory(mem3)

        // Memory 4: Shared Text
        val mem4 = MemoryItem(
            id = "mem-acc-4",
            title = "生活代辦",
            rawText = "週末記得買皇家貓糧幼貓配方",
            sourceType = SourceType.SHARED_TEXT
        )
        repository.saveMemory(mem4)

        // Memory 5: Shared URL
        val mem5 = MemoryItem(
            id = "mem-acc-5",
            title = "Kotlin GitHub",
            rawText = "The Kotlin Programming Language repository on GitHub",
            sourceUrl = "https://github.com/JetBrains/kotlin",
            sourceType = SourceType.SHARED_URL
        )
        repository.saveMemory(mem5)

        // Question 1: "我十月是不是有一場演唱會？"
        val res1 = retriever.retrieve(RetrievalQuery("我十月是不是有一場演唱會？", defaultOptions))
        assertFalse("Q1 must return results", res1.items.isEmpty())
        assertEquals("Q1 Top-1 must be Screenshot mem-acc-1", "mem-acc-1", res1.items[0].memoryId)
        assertTrue(res1.items[0].matchedSignals.contains(RetrievalSignal.EVENT_DATE_MATCH))

        // Question 2: "我跟張經理談了什麼？"
        val res2 = retriever.retrieve(RetrievalQuery("我跟張經理談了什麼？", defaultOptions))
        assertFalse("Q2 must return results", res2.items.isEmpty())
        assertEquals("Q2 Top-1 must be Recording mem-acc-3", "mem-acc-3", res2.items[0].memoryId)
        assertTrue(res2.items[0].matchedSignals.contains(RetrievalSignal.ENTITY_MATCH) || res2.items[0].matchedSignals.contains(RetrievalSignal.BODY_MATCH))

        // Question 3: "之前那個貓糧是什麼？"
        val res3 = retriever.retrieve(RetrievalQuery("之前那個貓糧是什麼？", defaultOptions))
        assertFalse("Q3 must return results", res3.items.isEmpty())
        assertEquals("Q3 Top-1 must be Shared Text mem-acc-4", "mem-acc-4", res3.items[0].memoryId)

        // Question 4: "我存過 GitHub 的東西嗎？"
        val res4 = retriever.retrieve(RetrievalQuery("我存過 GitHub 的東西嗎？", defaultOptions))
        assertFalse("Q4 must return results", res4.items.isEmpty())
        assertEquals("Q4 Top-1 must be Shared URL mem-acc-5", "mem-acc-5", res4.items[0].memoryId)
    }

    @Test
    fun lastWeekMeetingQuestionRetrievesRecordingFromLastWeek() = runBlocking {
        // Thursday 2026-10-15 reference: last week is 2026-10-05 to 2026-10-11
        val lastWeekTime = Instant.parse("2026-10-08T06:00:00Z").toEpochMilli() // Thursday Oct 8
        val oldMeetingTime = Instant.parse("2026-08-01T06:00:00Z").toEpochMilli()

        val recentMeeting = MemoryItem(
            id = "mem-recent-meeting",
            title = "產品架構會議",
            rawText = "與工程團隊討論系統延遲與微服務整合架構",
            capturedAt = lastWeekTime,
            sourceType = SourceType.RECORDING
        )
        val oldMeeting = MemoryItem(
            id = "mem-old-meeting",
            title = "年度規劃會議",
            rawText = "年度研討會籌備討論事項",
            capturedAt = oldMeetingTime,
            sourceType = SourceType.RECORDING
        )
        repository.saveMemory(oldMeeting)
        repository.saveMemory(recentMeeting)

        val res = retriever.retrieve(RetrievalQuery("上週會議討論了什麼？", defaultOptions))
        assertFalse("Results must not be empty", res.items.isEmpty())
        assertEquals("Top item must be the meeting from last week", "mem-recent-meeting", res.items[0].memoryId)
        assertTrue("Capture date match signal must be detected", res.items[0].matchedSignals.contains(RetrievalSignal.CAPTURE_DATE_MATCH))
        assertEquals(RetrievalConfidence.HIGH, res.confidence)
    }

    // ------------------------------------------------------------------------
    // 11. Benchmark Metrics Instrumentation (Section 35)
    // ------------------------------------------------------------------------

    @Test
    fun benchmarkMetricsArePopulatedAndNonNegative() = runBlocking {
        repository.saveMemory(MemoryItem(title = "Benchmark Item", rawText = "Testing latency instrumentation", sourceType = SourceType.SCREENSHOT))

        val result = retriever.retrieve(RetrievalQuery("Benchmark", defaultOptions))
        val metrics = result.metrics
        assertNotNull(metrics)
        assertTrue(metrics!!.candidateGenerationMs >= 0)
        assertTrue(metrics.rerankMs >= 0)
        assertTrue(metrics.totalLatencyMs >= 0)
        assertTrue(metrics.candidateCount >= 1)
        assertTrue(metrics.resultCount >= 1)
    }

    // ------------------------------------------------------------------------
    // 12. Retrieval Reliability Regressions (Section 1 - 5)
    // ------------------------------------------------------------------------

    @Test
    fun databaseReturnsBoundedEntitiesOnLargeMatchCount() = runBlocking {
        // Insert 1000 matching memories
        val baseTime = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        val batch = (1..1000).map { i ->
            MemoryItem(
                id = "mem-large-$i",
                title = "大量日誌記錄 $i",
                rawText = "這是一筆關於大規模資料檢索的測試記錄，包含關鍵字分散與資料庫壓力測試 $i",
                capturedAt = baseTime + (i * 1000L),
                sourceType = SourceType.SCREENSHOT
            )
        }
        batch.chunked(100).forEach { chunk ->
            chunk.forEach { repository.saveMemory(it) }
        }

        // Test bounded retrieval
        val result = retriever.retrieve(RetrievalQuery("大規模資料檢索", defaultOptions))
        assertTrue("Items must be bounded by MAX_TOP_K", result.items.size <= RetrievalPolicy.MAX_TOP_K)
        assertTrue("Total candidates found must be bounded by MAX_CANDIDATES", result.totalCandidatesFound <= RetrievalPolicy.MAX_CANDIDATES)

        // Verify direct SQL bounded query returns at most requested limit
        val boundedMatches = database.searchDao().searchMemoriesMatchBounded("大規模*", 20)
        assertTrue("Direct SQL query must respect limit 20", boundedMatches.size <= 20)
    }

    @Test
    fun olderHighlyRelevantMemoryBeatsManyNewerWeakMatches() = runBlocking {
        // Insert 500 newer weak matches (matching token '城堡', but irrelevant content)
        val recentTime = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
        (1..500).forEach { i ->
            repository.saveMemory(
                MemoryItem(
                    id = "mem-weak-$i",
                    title = "日常生活碎片 $i",
                    rawText = "今天在遊戲中蓋了一座城堡模型隨筆 $i",
                    capturedAt = recentTime + (i * 1000L),
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        // Insert 1 older highly relevant memory (6 months older, exact compound match)
        val olderTime = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()
        val highlyRelevantOlder = MemoryItem(
            id = "mem-highly-relevant-older",
            title = "捷克布拉格城堡旅遊全攻略",
            rawText = "親自造訪捷克布拉格城堡，聖維特大教堂與黃金巷遊覽全紀錄與重要歷史介紹",
            capturedAt = olderTime,
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(highlyRelevantOlder)

        val result = retriever.retrieve(RetrievalQuery("捷克布拉格城堡", defaultOptions))
        assertFalse("Must return items", result.items.isEmpty())
        assertEquals("Top-1 must be the older highly relevant memory, beating 500 newer weak matches",
            "mem-highly-relevant-older", result.items[0].memoryId)
        assertTrue("Must have title match signal", result.items[0].matchedSignals.contains(RetrievalSignal.TITLE_MATCH))
        assertEquals(RetrievalConfidence.HIGH, result.confidence)
    }

    @Test
    fun dateOnlyUnrelatedMemoryIsNotHighConfidence() = runBlocking {
        // Question: "我十月有演唱會嗎？"
        // Memory has October event (flight), but NO concert / 演唱會 mention at all
        val octFlightTime = Instant.parse("2026-10-15T09:00:00Z").toEpochMilli()
        val flightMemory = MemoryItem(
            id = "mem-flight-ticket",
            title = "台北飛東京長榮機票收據",
            rawText = "長榮航空 BR198 台北桃園至東京成田 機票票號 123456789",
            capturedAt = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli(),
            sourceType = SourceType.SCREENSHOT,
            eventCandidates = listOf(
                EventCandidate(
                    title = "台北飛東京航班",
                    startTimestamp = octFlightTime,
                    location = "桃園國際機場"
                )
            )
        )
        repository.saveMemory(flightMemory)

        val result = retriever.retrieve(RetrievalQuery("我十月有演唱會嗎？", defaultOptions))
        // Must NOT be rated HIGH confidence because there is ZERO thematic evidence for '演唱會'
        assertFalse("Date-only match with zero thematic evidence must not be HIGH confidence",
            result.confidence == RetrievalConfidence.HIGH)
    }

    @Test
    fun wholeSerializedContextRespectsBudget() = runBlocking {
        // Create 8 memories with long titles, URLs, and bodies
        (1..8).forEach { i ->
            repository.saveMemory(
                MemoryItem(
                    id = "mem-budget-$i",
                    title = "系統架構核心設計規格書與微服務拆解方案詳細報告第 $i 冊 " + "A".repeat(120),
                    rawText = "這是系統架構詳細規格內文說明，包含多資料庫一致性保證與事件驅動設計理念。" + "內容詳述 ".repeat(150),
                    sourceUrl = "https://internal.corp.cayana.com/docs/architecture/spec/v1/system-design-module-$i?token=secret123&client=android",
                    sourceType = SourceType.SCREENSHOT
                )
            )
        }

        val result = retriever.retrieve(RetrievalQuery("系統架構核心設計規格書", defaultOptions.copy(topK = 8)))
        assertFalse("Must return items", result.items.isEmpty())

        // Calculate total characters of the whole serialized context (title, URL, excerpt, eventSummary)
        val totalSerializedChars = result.items.sumOf { it.estimatedTotalChars() }
        assertTrue("Whole serialized context across all items ($totalSerializedChars) must not exceed MAX_TOTAL_CONTEXT_CHARS (${RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS})",
            totalSerializedChars <= RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS)

        // Verify URL was strictly sanitized: query string, fragment, and userinfo are stripped by default
        result.items.forEach { ctx ->
            assertFalse("Sensitive token in URL must be stripped", ctx.sourceUrl?.contains("secret123") == true)
            assertFalse("Query string in URL must be stripped by default", ctx.sourceUrl?.contains("?") == true)
            assertFalse("Query parameters in URL must be stripped by default", ctx.sourceUrl?.contains("client=android") == true)
        }
    }

    @Test
    fun oversizedMetadataCannotBypassContextLimit() = runBlocking {
        // Memory with 5,000 char title and 5,000 char URL
        val massiveTitle = "惡意超長標題 ".repeat(500)
        val massiveUrl = "https://example.com/very/long/path/" + "B".repeat(5000)
        val massiveBody = "實質內容文字資訊 ".repeat(200)

        repository.saveMemory(
            MemoryItem(
                id = "mem-oversized-meta",
                title = massiveTitle,
                rawText = massiveBody,
                sourceUrl = massiveUrl,
                sourceType = SourceType.SCREENSHOT
            )
        )

        val result = retriever.retrieve(RetrievalQuery("實質內容文字資訊", defaultOptions))
        assertEquals(1, result.items.size)
        val item = result.items[0]

        assertTrue("Title must be clamped to safe bound", (item.title?.length ?: 0) <= 200)
        assertTrue("URL must be clamped to safe bound", (item.sourceUrl?.length ?: 0) <= 300)
        assertTrue("Total item context must respect budget", item.estimatedTotalChars() <= RetrievalPolicy.MAX_TOTAL_CONTEXT_CHARS)
    }

    @Test
    fun veryLongQueryIsResourceBounded() = runBlocking {
        // Query of 10,000 characters
        val attackQuery = "測試無效長字串查詢檢索防禦機制 ".repeat(600)
        val start = System.currentTimeMillis()
        val result = retriever.retrieve(RetrievalQuery(attackQuery, defaultOptions))
        val duration = System.currentTimeMillis() - start

        assertTrue("Adversarial query execution must complete within 500ms (took ${duration}ms)", duration < 500)
        // Must not crash and must not dump entire database
        assertTrue("Adversarial query must not return unbounded items", result.items.size <= RetrievalPolicy.MAX_TOP_K)
    }

    @Test
    fun ftsIndexUpgradeFromV1ToV2PreservesDataAndPopulatesNewCapabilities() = runBlocking {
        val versionStorage = com.cayana.search.data.InMemorySearchIndexVersionStorage(initialVersion = 1)
        val upgradingRetriever = DefaultMemoryRetriever(
            memoryRepository = repository,
            searchDao = database.searchDao(),
            searchIndexStateDao = database.searchIndexStateDao(),
            searchIndexVersionStorage = versionStorage,
            logger = testLogger
        )

        val concertTime = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
        val entityMemory = MemoryItem(
            id = "mem-upgrade-test",
            title = "大會門票",
            rawText = "大會入場憑證",
            entities = listOf("GoogleDeepMind"),
            eventCandidates = listOf(
                EventCandidate(
                    title = "AI 開發者大會",
                    startTimestamp = concertTime,
                    location = "台北國際會議中心"
                )
            ),
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(entityMemory)

        // Clear FTS index to simulate old v1 state before entity indexing
        database.searchDao().deleteFtsByMemoryId("mem-upgrade-test")
        // Insert old-format FTS document missing entities/eventCandidates
        val oldFtsDoc = com.cayana.memory.data.MemoryFtsEntity(
            memoryId = "mem-upgrade-test",
            title = "大會門票",
            rawText = "大會入場憑證",
            normalizedText = "",
            sourceType = "SCREENSHOT",
            sourceUrl = "",
            host = "",
            displayName = "",
            searchTokens = "screenshot 大會門票 大會入場憑證"
        )
        database.searchDao().insertFts(oldFtsDoc)

        // Verify version is 1
        assertEquals(1, versionStorage.getIndexFormatVersion())

        // Retrieve by entity "GoogleDeepMind" (which was missing in old tokens)
        val res = upgradingRetriever.retrieve(RetrievalQuery("GoogleDeepMind", defaultOptions))
        assertFalse("Memory must be retrieved after auto-upgrade", res.items.isEmpty())
        assertEquals("mem-upgrade-test", res.items[0].memoryId)
        assertTrue(res.items[0].matchedSignals.contains(RetrievalSignal.ENTITY_MATCH))

        // Verify versionStorage was updated to v2
        assertEquals(RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION, versionStorage.getIndexFormatVersion())
    }

    @Test
    fun singleTermSearchOlderExactTitleBeatsNewerBodyMatches() = runBlocking {
        // 500 new memories whose rawText contains "城堡"
        val recentTime = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        val newMemories = (1..500).map { i ->
            MemoryItem(
                id = "mem-recent-castle-$i",
                title = "日常生活隨筆第 $i 篇",
                rawText = "今天在沙灘上堆了一座漂亮的城堡模型紀錄 $i",
                capturedAt = recentTime + (i * 1000L),
                sourceType = SourceType.SCREENSHOT
            )
        }
        database.memoryDao().insertAll(newMemories.map { com.cayana.memory.data.MemoryEntity.fromDomain(it) })
        database.searchDao().insertAllFts(newMemories.map { com.cayana.search.MemorySearchDocumentBuilder.buildDocument(it) })

        // 1 older memory whose title is EXACTLY "城堡"
        val olderTime = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val exactTitleOlder = MemoryItem(
            id = "mem-exact-title-castle-older",
            title = "城堡",
            rawText = "世界文化遺產城堡參觀歷史背景與建築結構詳細介紹",
            capturedAt = olderTime,
            sourceType = SourceType.SCREENSHOT
        )
        repository.saveMemory(exactTitleOlder)

        // Single term search "城堡"
        val result = retriever.retrieve(RetrievalQuery("城堡", defaultOptions))
        assertFalse("Retrieval result must not be empty", result.items.isEmpty())

        // Top-1 must be the older memory with exact title "城堡"
        val topItem = result.items[0]
        assertEquals("Top-1 must be the older memory with exact title '城堡'", "mem-exact-title-castle-older", topItem.memoryId)
        assertEquals("城堡", topItem.title)
        assertTrue("Must contain EXACT_TITLE signal", topItem.matchedSignals.contains(RetrievalSignal.EXACT_TITLE))
        assertEquals(RetrievalConfidence.HIGH, result.confidence)

        // Verify bounded candidate generation in SQL:
        val boundedFtsMatches = database.searchDao().searchMemoriesMatchBounded(ftsQuery = "城堡", limit = 15, term = "城堡")
        assertEquals("SQL candidate query must rank exact title first within LIMIT bound", "mem-exact-title-castle-older", boundedFtsMatches[0].id)
        assertTrue("Returned SQL candidates must be bounded by limit", boundedFtsMatches.size <= 15)
    }

    @Test
    fun rebuildFailureLeavesIndexDirtyAndRetriesOnNextRun() = runBlocking {
        val versionStorage = com.cayana.search.data.InMemorySearchIndexVersionStorage(initialVersion = 1)
        val realSearchDao = database.searchDao()

        // Wrap searchDao to simulate process death / disk exception during rebuild
        var shouldFailRebuild = true
        val faultInjectingSearchDao = object : com.cayana.search.data.SearchDao by realSearchDao {
            override suspend fun insertAllFts(entities: List<com.cayana.memory.data.MemoryFtsEntity>) {
                if (shouldFailRebuild) {
                    throw IllegalStateException("Simulated crash during batch FTS insertion")
                }
                realSearchDao.insertAllFts(entities)
            }
        }

        val testRepo = RoomMemoryRepository(
            memoryDao = database.memoryDao(),
            searchDao = faultInjectingSearchDao,
            searchIndexStateDao = database.searchIndexStateDao(),
            searchIndexVersionStorage = versionStorage,
            database = database
        )

        val testRetriever = DefaultMemoryRetriever(
            memoryRepository = testRepo,
            searchDao = faultInjectingSearchDao,
            searchIndexStateDao = database.searchIndexStateDao(),
            searchIndexVersionStorage = versionStorage,
            logger = testLogger
        )

        // Add a canonical memory
        testRepo.saveMemory(
            MemoryItem(
                id = "mem-rebuild-resilience",
                title = "關鍵修復筆記",
                rawText = "這是重要的一筆本機記憶",
                sourceType = SourceType.SCREENSHOT
            )
        )

        // Trigger rebuild while failure is active
        try {
            testRepo.rebuildSearchIndex()
        } catch (_: Exception) {
            // Expected simulated failure
        }

        // Verify state after interrupted rebuild:
        // 1. Persistent dirty state MUST be true
        assertEquals("SearchIndexStateDao must remain dirty after interrupted rebuild",
            true, database.searchIndexStateDao().isDirty())
        assertTrue("Repository must report rebuild needed", testRepo.isIndexRebuildNeeded())
        // 2. Format version must NOT be updated while dirty
        assertEquals("Index format version must NOT be updated on failure", 1, versionStorage.getIndexFormatVersion())

        // Now resolve fault (e.g. process restart / healthy retry)
        shouldFailRebuild = false

        // Next retrieval or rebuild call should automatically repair index
        val repairResult = testRetriever.retrieve(RetrievalQuery("關鍵修復筆記", defaultOptions))
        assertFalse("Must retrieve item after automatic index repair", repairResult.items.isEmpty())
        assertEquals("mem-rebuild-resilience", repairResult.items[0].memoryId)

        // Verify index is now clean and format version is updated
        assertEquals("Dirty flag must be cleared after successful repair",
            false, database.searchIndexStateDao().isDirty())
        assertFalse("Repository must report clean index", testRepo.isIndexRebuildNeeded())
        assertEquals("Format version must be upgraded to current version",
            RetrievalPolicy.CURRENT_INDEX_FORMAT_VERSION, versionStorage.getIndexFormatVersion())

        // Subsequent normal retrieval must not trigger rebuild
        val normalResult = testRetriever.retrieve(RetrievalQuery("關鍵修復筆記", defaultOptions))
        assertEquals("mem-rebuild-resilience", normalResult.items[0].memoryId)
        assertFalse("Repository index remains clean", testRepo.isIndexRebuildNeeded())
    }
}
