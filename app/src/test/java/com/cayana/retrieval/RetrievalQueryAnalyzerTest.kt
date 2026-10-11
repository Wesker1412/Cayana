package com.cayana.retrieval

import com.cayana.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RetrievalQueryAnalyzerTest {

    private val fixedZone = ZoneId.of("Asia/Taipei")
    // Fixed instant: 2026-10-15 12:00:00 UTC (2026-10-15 20:00:00 in Asia/Taipei)
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), fixedZone)
    private val defaultOptions = RetrievalOptions(clock = fixedClock, zoneId = fixedZone)

    @Test
    fun emptyOrBlankQueryIsGenericOrEmpty() {
        val res1 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("", defaultOptions))
        assertTrue(res1.isGenericOrEmpty)

        val res2 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("   ", defaultOptions))
        assertTrue(res2.isGenericOrEmpty)
    }

    @Test
    fun noiseOnlyQueryIsGenericOrEmpty() {
        val q1 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("那個", defaultOptions))
        assertTrue("那個 must be detected as generic or empty", q1.isGenericOrEmpty)

        val q2 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("之前的", defaultOptions))
        assertTrue("之前的 must be detected as generic or empty", q2.isGenericOrEmpty)

        val q3 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("我記得", defaultOptions))
        assertTrue("我記得 must be detected as generic or empty", q3.isGenericOrEmpty)

        val q4 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("請問一下那個之前", defaultOptions))
        assertTrue("Combination of noise words must be detected as generic", q4.isGenericOrEmpty)
    }

    @Test
    fun naturalQuestionStripsNoiseAndExtractsContentTerms() {
        val q = RetrievalQueryAnalyzer.analyze(RetrievalQuery("我之前截圖的那個捷克房子", defaultOptions))
        assertFalse(q.isGenericOrEmpty)
        assertEquals(SourceType.SCREENSHOT, q.sourceHint)
        assertTrue(q.contentTerms.any { it.contains("捷克") || it.contains("房子") })
    }

    @Test
    fun extractsExplicitMonthInChineseAndArabicNumerals() {
        // Arabic: "10月"
        val q1 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("我10月是不是有一場演唱會？", defaultOptions))
        assertNotNull(q1.timeHint)
        assertEquals("10月", q1.timeHint?.label)
        assertTrue(q1.timeTokens.contains("10月"))
        assertTrue(q1.timeTokens.contains("十月"))
        assertTrue(q1.contentTerms.contains("演唱會"))

        // Chinese: "十月"
        val q2 = RetrievalQueryAnalyzer.analyze(RetrievalQuery("我十月是不是有一場演唱會？", defaultOptions))
        assertNotNull(q2.timeHint)
        assertEquals("十月", q2.timeHint?.label)
        assertTrue(q2.timeTokens.contains("10月"))
        assertTrue(q2.timeTokens.contains("十月"))
        assertTrue(q2.contentTerms.contains("演唱會"))
    }

    @Test
    fun extractsExplicitYearAndMonth() {
        val q = RetrievalQueryAnalyzer.analyze(RetrievalQuery("2026年10月的會議紀錄", defaultOptions))
        assertNotNull(q.timeHint)
        assertEquals("2026年10月", q.timeHint?.label)
        assertTrue(q.timeTokens.contains("2026年10月"))
        assertTrue(q.timeTokens.contains("10月"))
        assertTrue(q.contentTerms.contains("會議紀錄"))
    }

    @Test
    fun extractsRelativeTimeHints() {
        val qThisYear = RetrievalQueryAnalyzer.analyze(RetrievalQuery("今年買了什麼書", defaultOptions))
        assertNotNull(qThisYear.timeHint)
        assertEquals("今年", qThisYear.timeHint?.label)
        assertTrue(qThisYear.timeTokens.contains("2026"))

        val qLastMonth = RetrievalQueryAnalyzer.analyze(RetrievalQuery("上個月的居酒屋消費", defaultOptions))
        assertNotNull(qLastMonth.timeHint)
        assertEquals("上個月", qLastMonth.timeHint?.label)
        assertTrue(qLastMonth.timeTokens.contains("9月"))
    }

    @Test
    fun extractsSourceHintsCorrectly() {
        val qScreenshot = RetrievalQueryAnalyzer.analyze(RetrievalQuery("截圖發票", defaultOptions))
        assertEquals(SourceType.SCREENSHOT, qScreenshot.sourceHint)

        val qPhoto = RetrievalQueryAnalyzer.analyze(RetrievalQuery("拍的照片菜單", defaultOptions))
        assertEquals(SourceType.PHOTO, qPhoto.sourceHint)

        val qRecording = RetrievalQueryAnalyzer.analyze(RetrievalQuery("錄音討論合約", defaultOptions))
        assertEquals(SourceType.RECORDING, qRecording.sourceHint)

        val qUrl = RetrievalQueryAnalyzer.analyze(RetrievalQuery("存過的網址連結", defaultOptions))
        assertEquals(SourceType.SHARED_URL, qUrl.sourceHint)
    }

    @Test
    fun preservesOptionFilterSourceTypeOverInferredHint() {
        val options = defaultOptions.copy(filterSourceType = SourceType.PHOTO)
        val q = RetrievalQueryAnalyzer.analyze(RetrievalQuery("錄音討論合約", options))
        // Explicit filter takes precedence
        assertEquals(SourceType.PHOTO, q.sourceHint)
    }
}
