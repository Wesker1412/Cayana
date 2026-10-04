package com.cayana.event.extractor

import com.cayana.event.model.ConfidenceLevel
import com.cayana.event.model.DateRole
import com.cayana.event.model.EventActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MultiDateSafetyTest {

    private val zoneId = ZoneId.of("Asia/Taipei")
    private val extractor = DeterministicEventExtractor()
    private val fixedRefTime = ZonedDateTime.of(
        LocalDate.of(2026, 10, 5),
        java.time.LocalTime.of(10, 0),
        zoneId
    ).toInstant()

    @Test
    fun deadlineAndEventPrioritizesEvent() {
        val text = """
            早鳥截止 10/15
            XX Live
            活動日期 10/25 19:30
            台北流行音樂中心
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(2, candidates.size)

        // Top candidate must be the actual event, NOT the deadline!
        val topCandidate = candidates.first()
        assertEquals(DateRole.EVENT_TIME, topCandidate.dateRole)

        val expectedEventDate = ZonedDateTime.of(2026, 10, 25, 19, 30, 0, 0, zoneId).toInstant()
        val deadlineDate = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zoneId).toInstant()

        assertEquals(expectedEventDate, topCandidate.startAt)
        assertNotEquals(deadlineDate, topCandidate.startAt)

        val policy = EventActionPolicy.evaluate(topCandidate, fixedRefTime)
        assertEquals(ConfidenceLevel.HIGH, policy)
    }

    @Test
    fun multiEventDoesNotAutoAdd() {
        val text = """
            2026巡迴演唱會
            台北場 10/18 19:00
            高雄場 10/19 19:00
        """.trimIndent()

        val candidates = extractor.extract(text, fixedRefTime, zoneId)
        assertEquals(2, candidates.size)

        // Multiple distinct event dates must never be HIGH confidence auto-added
        for (candidate in candidates) {
            val policy = EventActionPolicy.evaluate(candidate, fixedRefTime)
            assertNotEquals(ConfidenceLevel.HIGH, policy)
            assertEquals(ConfidenceLevel.MEDIUM, policy)
        }
    }
}
