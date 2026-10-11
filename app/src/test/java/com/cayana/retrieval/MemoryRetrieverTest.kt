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
}
