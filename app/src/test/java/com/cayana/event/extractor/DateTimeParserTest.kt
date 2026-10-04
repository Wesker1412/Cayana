package com.cayana.event.extractor

import com.cayana.event.model.ConfidenceLevel
import com.cayana.event.model.DateRole
import com.cayana.event.model.EventActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateTimeParserTest {

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val extractor = DeterministicEventExtractor()

    // Reference clock fixed at: Monday, 2026-10-05 10:00:00 Asia/Taipei
    private val fixedRefDate = LocalDate.of(2026, 10, 5)
    private val fixedRefTime = ZonedDateTime.of(fixedRefDate, java.time.LocalTime.of(10, 0), zoneId).toInstant()

    @Test
    fun parseSlashDateWithTime() {
        val text = """
            XX Live
            10/18 19:30
            台北流行音樂中心
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        assertEquals("XX Live", event.title)
        assertEquals("台北流行音樂中心", event.location)
        assertEquals(DateRole.EVENT_TIME, event.dateRole)

        val expectedStart = ZonedDateTime.of(2026, 10, 18, 19, 30, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        assertFalse(event.isAllDay)
        assertTrue(event.endTimeInferred)

        val policy = EventActionPolicy.evaluate(event, fixedRefTime)
        assertEquals(ConfidenceLevel.HIGH, policy)
    }

    @Test
    fun parseChineseDateWithTime() {
        val text = """
            搖滾音樂祭
            10月18日 19:30
            台北小巨蛋
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2026, 10, 18, 19, 30, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        assertEquals(DateRole.EVENT_TIME, event.dateRole)
    }

    @Test
    fun parseChineseDateWithChineseTime() {
        val text = """
            年度技術分享會
            10月18日下午2點
            南港展覽館
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2026, 10, 18, 14, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
    }

    @Test
    fun parseSlashDateWithChineseHalfTime() {
        val text = """
            XX Live
            10/18 晚上7點半
            台北流行音樂中心
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2026, 10, 18, 19, 30, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
    }

    @Test
    fun parseFullYearDates() {
        val text1 = "國際論壇 2026/10/18 19:30 台北國際會議中心"
        val candidates1 = extractor.extract(text1, fixedRefTime, zoneId)
        assertEquals(1, candidates1.size)
        val expectedStart1 = ZonedDateTime.of(2026, 10, 18, 19, 30, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart1, candidates1.first().startAt)

        val text2 = "專題演講 2026-10-18 19:30 台北小巨蛋"
        val candidates2 = extractor.extract(text2, fixedRefTime, zoneId)
        assertEquals(1, candidates2.size)
        assertEquals(expectedStart1, candidates2.first().startAt)
    }

    @Test
    fun parseRelativeTomorrowWithTime() {
        // ref: Monday 2026-10-05 -> tomorrow is Tuesday 2026-10-06
        val text = "小組會議 明天下午兩點 總部會議室"
        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val expectedStart = ZonedDateTime.of(2026, 10, 6, 14, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, candidates.first().startAt)
    }

    @Test
    fun parseRelativeNextFridayNight() {
        // ref: Monday 2026-10-05 -> next week Friday is 2026-10-16
        val text = "爵士音樂夜 下週五晚上七點 國家音樂廳"
        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val expectedStart = ZonedDateTime.of(2026, 10, 16, 19, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, candidates.first().startAt)
    }

    @Test
    fun parseRelativeWeekdayWithTime() {
        // ref: Monday 2026-10-05 -> 週六 is Saturday 2026-10-10
        val text1 = "週末市集 週六 14:00 松山文創園區"
        val candidates1 = extractor.extract(text1, fixedRefTime, zoneId)
        assertEquals(1, candidates1.size)
        val expectedStart1 = ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart1, candidates1.first().startAt)

        // ref: Monday 2026-10-05 -> 星期日 is Sunday 2026-10-11
        val text2 = "野餐同樂會 星期日下午三點 大安森林公園"
        val candidates2 = extractor.extract(text2, fixedRefTime, zoneId)
        assertEquals(1, candidates2.size)
        val expectedStart2 = ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart2, candidates2.first().startAt)
    }

    @Test
    fun yearRolloverInference() {
        // Reference date is December 30, 2026
        val dec30Ref = ZonedDateTime.of(2026, 12, 30, 10, 0, 0, 0, zoneId).toInstant()
        val text = "新年音樂會 1/5 14:00 國家兩廳院"

        val candidates = extractor.extract(text, dec30Ref, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        // Month 1 < Month 12 -> infers 2027-01-05
        val expectedStart = ZonedDateTime.of(2027, 1, 5, 14, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        val policy = EventActionPolicy.evaluate(event, dec30Ref)
        assertEquals(ConfidenceLevel.HIGH, policy)
    }

    @Test
    fun pastDateLowConfidenceNoAutoAdd() {
        // Reference date is 2026-10-20
        val oct20Ref = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zoneId).toInstant()
        // Event date is 10/18 (2 days in the past)
        val text = "XX Live 10/18 19:30 台北流行音樂中心"

        val candidates = extractor.extract(text, oct20Ref, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val policy = EventActionPolicy.evaluate(event, oct20Ref)
        assertEquals(ConfidenceLevel.LOW, policy)
    }

    @Test
    fun previousMonthRecentPastInference() {
        // Reference date is 2026-10-05; date 9/30 is 5 days ago in the same year 2026 (not 2027)
        val candidates = extractor.extract("秋季分享會 9/30 19:30 台北小巨蛋", fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2026, 9, 30, 19, 30, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        // Since it is in the past, policy must be LOW
        assertEquals(ConfidenceLevel.LOW, EventActionPolicy.evaluate(event, fixedRefTime))
    }

    @Test
    fun decemberToJanuaryRolloverInference() {
        val dec30Ref = ZonedDateTime.of(2026, 12, 30, 10, 0, 0, 0, zoneId).toInstant()
        val candidates = extractor.extract("新年音樂會 1/5 14:00 國家兩廳院", dec30Ref, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2027, 1, 5, 14, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        assertEquals(ConfidenceLevel.HIGH, EventActionPolicy.evaluate(event, dec30Ref))
    }

    @Test
    fun januaryToDecemberRecentPastInference() {
        // Reference date is 2027-01-03; 12/31 is 3 days ago in 2026 (not 2027)
        val jan03Ref = ZonedDateTime.of(2027, 1, 3, 10, 0, 0, 0, zoneId).toInstant()
        val candidates = extractor.extract("跨年音樂派對 12/31 22:00 台北101", jan03Ref, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()

        val expectedStart = ZonedDateTime.of(2026, 12, 31, 22, 0, 0, 0, zoneId).toInstant()
        assertEquals(expectedStart, event.startAt)
        // In the past -> LOW
        assertEquals(ConfidenceLevel.LOW, EventActionPolicy.evaluate(event, jan03Ref))
    }
}
