package com.cayana.event.extractor

import com.cayana.event.model.ConfidenceLevel
import com.cayana.event.model.DateRole
import com.cayana.event.model.EventActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateRoleClassificationTest {

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val extractor = DeterministicEventExtractor()
    private val fixedRefTime = ZonedDateTime.of(
        LocalDate.of(2026, 10, 5),
        java.time.LocalTime.of(10, 0),
        zoneId
    ).toInstant()

    @Test
    fun classifyEventTime() {
        val text = "活動日期 10/25 19:00 台北流行音樂中心"
        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()
        assertEquals(DateRole.EVENT_TIME, event.dateRole)
        assertEquals(ConfidenceLevel.HIGH, EventActionPolicy.evaluate(event, fixedRefTime))
    }

    @Test
    fun classifyRegistrationDeadline() {
        val text = "黑客松競賽 報名截止 10/20 23:59 線上報名"
        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()
        assertEquals(DateRole.REGISTRATION_DEADLINE, event.dateRole)
        // Deadlines must not be treated as EVENT_TIME, policy evaluates to LOW
        assertEquals(ConfidenceLevel.LOW, EventActionPolicy.evaluate(event, fixedRefTime))
    }

    @Test
    fun classifySalePeriod() {
        val text = "演唱會門票 早鳥到 10/15 23:59 售票系統"
        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()
        assertEquals(DateRole.SALE_PERIOD, event.dateRole)
        // Sale periods must not be automatically added as events
        assertEquals(ConfidenceLevel.LOW, EventActionPolicy.evaluate(event, fixedRefTime))
    }

    @Test
    fun classifyBusinessHours() {
        val text = """
            咖啡廳資訊
            營業時間
            週一至週五 09:00–18:00
            週六 10:00–17:00
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(1, candidates.size)
        val event = candidates.first()
        assertEquals(DateRole.BUSINESS_HOURS, event.dateRole)
        assertEquals(ConfidenceLevel.LOW, EventActionPolicy.evaluate(event, fixedRefTime))
    }
}
