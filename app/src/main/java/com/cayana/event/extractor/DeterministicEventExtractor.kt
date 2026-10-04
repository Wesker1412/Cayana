package com.cayana.event.extractor

import com.cayana.event.model.DateRole
import com.cayana.event.model.EventCandidate
import com.cayana.event.model.EventEvidence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Deterministic, rule-based EventExtractor for Traditional Chinese and standard numeric formats.
 * Strictly avoids LLM or network calls.
 * All relative date operations require explicit referenceTime and zoneId.
 */
class DeterministicEventExtractor : EventExtractor {

    override fun extract(
        text: String,
        referenceTime: Instant,
        zoneId: ZoneId
    ): List<EventCandidate> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val refZoned = referenceTime.atZone(zoneId)
        val refDate = refZoned.toLocalDate()

        // 1. Detect Business Hours early
        if (isBusinessHours(trimmed)) {
            val title = extractTitle(lines)
            return listOf(
                EventCandidate(
                    title = title,
                    startAt = null,
                    endAt = null,
                    location = extractLocation(lines),
                    isAllDay = false,
                    confidence = 0.10f,
                    evidence = listOf(
                        EventEvidence(
                            rawSnippet = extractFirstMatchingSnippet(trimmed, BUSINESS_HOURS_PATTERNS),
                            matchedPattern = "BUSINESS_HOURS",
                            role = DateRole.BUSINESS_HOURS
                        )
                    ),
                    dateRole = DateRole.BUSINESS_HOURS,
                    zoneId = zoneId
                )
            )
        }

        // 2. Parse all date/time matches across lines
        val parsedMatches = mutableListOf<ParsedDateMatch>()

        for ((lineIndex, line) in lines.withIndex()) {
            val matchesInLine = parseLineForDates(lines, lineIndex, refDate, zoneId)
            parsedMatches.addAll(matchesInLine)
        }

        if (parsedMatches.isEmpty()) {
            return emptyList()
        }

        // 3. Classify roles prioritizing current line and preventing adjacent line pollution
        val classified = parsedMatches.map { match ->
            val role = resolveRoleForLine(lines, match.lineIndex)
            match.copy(role = role)
        }

        // 4. Extract common attributes: Title, Location
        val title = extractTitle(lines)
        val location = extractLocation(lines)

        // 5. Check if there are multiple EVENT_TIME dates (e.g. 台北場 10/18, 高雄場 10/19)
        val eventTimeMatches = classified.filter { it.role == DateRole.EVENT_TIME }
        val isMultiEvent = eventTimeMatches.map { it.startDate }.distinct().size > 1

        val candidates = mutableListOf<EventCandidate>()

        for (match in classified) {
            val isAllDay = match.startTime == null
            val startInstant = if (isAllDay) {
                match.startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
            } else {
                ZonedDateTime.of(match.startDate, match.startTime, zoneId).toInstant()
            }

            val (endInstant, endTimeInferred) = if (isAllDay) {
                Pair(match.startDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), true)
            } else if (match.endTime != null) {
                val endZdt = ZonedDateTime.of(match.startDate, match.endTime, zoneId)
                Pair(endZdt.toInstant(), false)
            } else {
                Pair(startInstant.plusSeconds(3600), true) // Default 1 hour duration
            }

            val isPast = if (isAllDay) {
                match.startDate.isBefore(refDate)
            } else {
                startInstant.isBefore(referenceTime)
            }

            // Calculate confidence
            val confidence = when {
                match.role == DateRole.BUSINESS_HOURS -> 0.10f
                match.role == DateRole.DEADLINE || match.role == DateRole.REGISTRATION_DEADLINE || match.role == DateRole.SALE_PERIOD -> 0.40f
                isPast -> 0.20f // Past event -> low confidence, no auto calendar write
                isMultiEvent -> 0.60f // Multiple events -> capped at MEDIUM, never auto-add arbitrary first
                isAllDay -> 0.60f // Date without time: isAllDay = true, capped at MEDIUM (no auto-add without time)
                match.role == DateRole.EVENT_TIME && title != null -> 0.90f // HIGH!
                else -> 0.50f
            }

            candidates.add(
                EventCandidate(
                    title = title,
                    startAt = startInstant,
                    endAt = endInstant,
                    location = location,
                    isAllDay = isAllDay,
                    confidence = confidence,
                    evidence = listOf(
                        EventEvidence(
                            rawSnippet = match.rawText,
                            matchedPattern = match.patternTag,
                            role = match.role
                        )
                    ),
                    dateRole = match.role,
                    endTimeInferred = endTimeInferred,
                    zoneId = zoneId
                )
            )
        }

        // Return candidates with EVENT_TIME prioritized first
        return candidates.sortedWith(
            compareByDescending<EventCandidate> { it.dateRole == DateRole.EVENT_TIME }
                .thenByDescending { it.confidence }
        )
    }

    private fun isBusinessHours(text: String): Boolean {
        if (text.contains("營業時間") || text.contains("門市時間") || text.contains("營業:")) {
            return true
        }
        if ((text.contains("週一至週五") || text.contains("星期一至五") || text.contains("平日")) &&
            (text.contains("09:00") || text.contains("18:00") || text.contains("9:00")) &&
            !text.contains("活動日期") && !text.contains("開演")
        ) {
            return true
        }
        return false
    }

    private fun extractFirstMatchingSnippet(text: String, patterns: List<Regex>): String {
        for (pattern in patterns) {
            pattern.find(text)?.let { return it.value }
        }
        return text.take(30)
    }

    private fun parseLineForDates(
        lines: List<String>,
        lineIndex: Int,
        refDate: LocalDate,
        zoneId: ZoneId
    ): List<ParsedDateMatch> {
        val line = lines[lineIndex]
        val results = mutableListOf<ParsedDateMatch>()
        val contextSnippet = listOfNotNull(
            lines.getOrNull(lineIndex - 1),
            line,
            lines.getOrNull(lineIndex + 1)
        ).joinToString(" ")

        fun resolveTime(): Pair<LocalTime?, LocalTime?> {
            val (t1, t2) = parseTimeFromLine(line)
            if (t1 != null) return Pair(t1, t2)
            val nextLine = lines.getOrNull(lineIndex + 1) ?: return Pair(null, null)
            if (lineHasDate(nextLine)) return Pair(null, null)
            return parseTimeFromLine(nextLine)
        }

        // 1. Check relative date keywords (今天, 明天, 後天, 下週五, 週六, etc.)
        for (relRegex in RELATIVE_DATE_PATTERNS) {
            val match = relRegex.find(line)
            if (match != null) {
                val matchedKeyword = match.value
                val date = resolveRelativeDate(matchedKeyword, refDate)
                if (date != null) {
                    val (startTime, endTime) = resolveTime()
                    results.add(
                        ParsedDateMatch(
                            startDate = date,
                            startTime = startTime,
                            endTime = endTime,
                            rawText = line,
                            patternTag = "RELATIVE_DATE: $matchedKeyword",
                            contextSnippet = contextSnippet,
                            lineIndex = lineIndex
                        )
                    )
                    return results // Avoid duplicate parsing of same line
                }
            }
        }

        // 2. Check full absolute date: YYYY/MM/DD or YYYY-MM-DD or YYYY.MM.DD or YYYY年M月D日
        val fullDateMatch = FULL_DATE_REGEX.find(line)
        if (fullDateMatch != null) {
            val year = (fullDateMatch.groupValues[1].ifEmpty { fullDateMatch.groupValues[4] }).toInt()
            val month = (fullDateMatch.groupValues[2].ifEmpty { fullDateMatch.groupValues[5] }).toInt()
            val day = (fullDateMatch.groupValues[3].ifEmpty { fullDateMatch.groupValues[6] }).toInt()
            if (isValidDate(year, month, day)) {
                val date = LocalDate.of(year, month, day)
                val (startTime, endTime) = resolveTime()
                results.add(
                    ParsedDateMatch(
                        startDate = date,
                        startTime = startTime,
                        endTime = endTime,
                        rawText = fullDateMatch.value,
                        patternTag = "FULL_DATE",
                        contextSnippet = contextSnippet,
                        lineIndex = lineIndex
                    )
                )
                return results
            }
        }

        // 3. Check month/day: M/D or M月D日
        val monthDayMatch = MONTH_DAY_REGEX.find(line)
        if (monthDayMatch != null) {
            val month = (monthDayMatch.groupValues[1].ifEmpty { monthDayMatch.groupValues[3] }).toInt()
            val day = (monthDayMatch.groupValues[2].ifEmpty { monthDayMatch.groupValues[4] }).toInt()
            val year = inferYear(month, day, refDate)
            if (isValidDate(year, month, day)) {
                val date = LocalDate.of(year, month, day)
                val (startTime, endTime) = resolveTime()
                results.add(
                    ParsedDateMatch(
                        startDate = date,
                        startTime = startTime,
                        endTime = endTime,
                        rawText = monthDayMatch.value,
                        patternTag = "MONTH_DAY",
                        contextSnippet = contextSnippet,
                        lineIndex = lineIndex
                    )
                )
                return results
            }
        }

        return results
    }

    private fun parseTimeFromLine(line: String): Pair<LocalTime?, LocalTime?> {
        // Check for time range: e.g. 19:30 - 21:30 or 14:00~16:00 or 下午2點至4點
        val rangeMatch = TIME_RANGE_REGEX.find(line)
        if (rangeMatch != null) {
            val t1 = parseSingleTime(rangeMatch.groupValues[1])
            val t2 = parseSingleTime(rangeMatch.groupValues[2])
            if (t1 != null) {
                return Pair(t1, t2)
            }
        }

        // Check for single time
        val singleMatch = SINGLE_TIME_REGEX.find(line)
        if (singleMatch != null) {
            val time = parseSingleTime(singleMatch.value)
            if (time != null) {
                return Pair(time, null)
            }
        }

        return Pair(null, null)
    }

    private fun parseSingleTime(str: String): LocalTime? {
        val trimmed = str.trim()

        // 1. Standard 24h: HH:mm
        val colonMatch = Regex("""(\d{1,2}):(\d{2})""").find(trimmed)
        if (colonMatch != null) {
            val hour = colonMatch.groupValues[1].toInt()
            val min = colonMatch.groupValues[2].toInt()
            var adjustedHour = hour
            if (trimmed.contains("下午") || trimmed.contains("晚上") || trimmed.contains("晚間") || trimmed.contains("夜間")) {
                if (adjustedHour in 1..11) adjustedHour += 12
            }
            if (adjustedHour in 0..23 && min in 0..59) {
                return LocalTime.of(adjustedHour, min)
            }
        }

        // 2. Chinese time format: (下午|晚上|早上|上午|中午)? [0-9兩一二三四五六七八九十]+ 點 (半|[0-9]+分?)?
        val chineseTimeMatch = Regex("""(早上|上午|中午|下午|晚上|晚間|夜間)?\s*([0-9]{1,2}|[一二兩三四五六七八九十]+)\s*點\s*(半|[0-9]{1,2}\s*分?)?""").find(trimmed)
        if (chineseTimeMatch != null) {
            val period = chineseTimeMatch.groupValues[1]
            val hourStr = chineseTimeMatch.groupValues[2]
            val minStr = chineseTimeMatch.groupValues[3]

            var hour = ChineseNumberParser.parseChineseInt(hourStr) ?: return null
            var min = 0

            if (minStr == "半") {
                min = 30
            } else if (minStr.isNotEmpty()) {
                val cleanMinStr = minStr.replace("分", "").trim()
                min = cleanMinStr.toIntOrNull() ?: ChineseNumberParser.parseChineseInt(cleanMinStr) ?: 0
            }

            if (period == "下午" || period == "晚上" || period == "晚間" || period == "夜間") {
                if (hour in 1..11) hour += 12
            } else if (period == "中午" && hour == 1) {
                hour = 13
            }

            if (hour in 0..23 && min in 0..59) {
                return LocalTime.of(hour, min)
            }
        }

        return null
    }

    private fun resolveRelativeDate(keyword: String, refDate: LocalDate): LocalDate? {
        val trimmed = keyword.trim()
        return when {
            trimmed.contains("今天") -> refDate
            trimmed.contains("明天") -> refDate.plusDays(1)
            trimmed.contains("後天") -> refDate.plusDays(2)
            trimmed.startsWith("下週") || trimmed.startsWith("下星期") || trimmed.startsWith("下禮拜") -> {
                val dowChar = trimmed.last()
                val targetDow = ChineseNumberParser.parseDayOfWeek(dowChar) ?: return null
                val currentDow = refDate.dayOfWeek.value
                val daysUntilNextWeek = (7 - currentDow) + targetDow
                refDate.plusDays(daysUntilNextWeek.toLong())
            }
            trimmed.startsWith("週") || trimmed.startsWith("星期") || trimmed.startsWith("禮拜") || trimmed.startsWith("本週") -> {
                val dowChar = trimmed.last()
                val targetDow = ChineseNumberParser.parseDayOfWeek(dowChar) ?: return null
                var diff = targetDow - refDate.dayOfWeek.value
                if (diff <= 0) diff += 7
                refDate.plusDays(diff.toLong())
            }
            else -> null
        }
    }

    /**
     * Nearest-date year inference for M/D:
     * Evaluates [refYear - 1, refYear, refYear + 1] and picks candidate with minimal abs days difference.
     * Ties pick earlier date.
     */
    fun inferYear(month: Int, day: Int, refDate: LocalDate): Int {
        val refYear = refDate.year
        val candidateYears = listOf(refYear - 1, refYear, refYear + 1)
        val validCandidates = candidateYears.mapNotNull { y ->
            try {
                LocalDate.of(y, month, day)
            } catch (_: Exception) {
                null
            }
        }
        if (validCandidates.isEmpty()) return refYear
        return validCandidates.minWith(
            compareBy<LocalDate> { abs(ChronoUnit.DAYS.between(refDate, it)) }
                .thenBy { it }
        ).year
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        return try {
            LocalDate.of(year, month, day)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resolveRoleForLine(lines: List<String>, lineIndex: Int): DateRole {
        val currentLine = lines[lineIndex]
        val currentRole = findExplicitRole(currentLine)
        if (currentRole != null) {
            return currentRole
        }
        // Adjacent lines only inspected if current line has NO keyword
        // Adjacent line containing another independent date MUST NEVER provide role context
        val prevLine = lines.getOrNull(lineIndex - 1)
        if (prevLine != null && !lineHasDate(prevLine)) {
            val prevRole = findExplicitRole(prevLine)
            if (prevRole != null) return prevRole
        }
        val nextLine = lines.getOrNull(lineIndex + 1)
        if (nextLine != null && !lineHasDate(nextLine)) {
            val nextRole = findExplicitRole(nextLine)
            if (nextRole != null) return nextRole
        }
        return DateRole.EVENT_TIME
    }

    fun findExplicitRole(text: String): DateRole? {
        if (text.contains("營業時間") || text.contains("門市時間") || text.contains("營業:") || text.contains("營業：")) {
            return DateRole.BUSINESS_HOURS
        }
        if (text.contains("報名截止") || text.contains("截止時間") || text.contains("報名期限") ||
            text.contains("截止：") || text.contains("截止:") || text.contains("載止：") || text.contains("載止:") ||
            text.contains("截止") || text.contains("載止") || text.contains("期限") || text.contains("報名")
        ) {
            return DateRole.REGISTRATION_DEADLINE
        }
        if (text.contains("早鳥截止") || text.contains("早鳥優惠到") || text.contains("早鳥至") ||
            text.contains("優惠到") || text.contains("販售至") || text.contains("折扣至") ||
            text.contains("早鳥") || text.contains("開賣")
        ) {
            return DateRole.SALE_PERIOD
        }
        if (text.contains("活動日期") || text.contains("活動時間") || text.contains("開演") ||
            text.contains("演出時間") || text.contains("演出日期") || text.contains("時間：") ||
            text.contains("時間:") || text.contains("日期：") || text.contains("日期:")
        ) {
            return DateRole.EVENT_TIME
        }
        return null
    }

    fun lineHasDate(text: String): Boolean {
        if (FULL_DATE_REGEX.containsMatchIn(text)) return true
        if (MONTH_DAY_REGEX.containsMatchIn(text)) return true
        for (pattern in RELATIVE_DATE_PATTERNS) {
            if (pattern.containsMatchIn(text)) return true
        }
        return false
    }

    fun classifyDateRole(contextSnippet: String): DateRole {
        return findExplicitRole(contextSnippet) ?: DateRole.EVENT_TIME
    }

    /**
     * Deterministic title extraction:
     * Excludes UI elements, dates, times, URLs, and location lines, selecting the primary event line.
     */
    fun extractTitle(lines: List<String>): String? {
        val cleanCandidates = lines.filter { line ->
            !isIgnoredTitleLine(line)
        }
        val firstChoice = cleanCandidates.firstOrNull() ?: lines.firstOrNull()
        return firstChoice?.take(60)?.trim()
    }

    private fun isIgnoredTitleLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2) return true
        // Status bar & timestamp
        if (Regex("""^(\d{1,2}:\d{2}|100%|WiFi|LTE|5G|Settings|Cayana).*$""", RegexOption.IGNORE_CASE).matches(t)) return true
        // URLs & Emails & Browser address bars
        if (t.contains("http://", ignoreCase = true) || t.contains("https://", ignoreCase = true) ||
            t.contains("www.", ignoreCase = true) || t.contains("localhost", ignoreCase = true) ||
            t.contains("locallhost", ignoreCase = true) || t.contains(".com", ignoreCase = true) ||
            Regex("""^.*(localhost|\:\d{2,5}/).*$""", RegexOption.IGNORE_CASE).matches(t)
        ) return true
        // Obvious CTAs / UI Text
        if (t.contains("立即報名") || t.contains("立即購票") || t.contains("點擊連結") || t.contains("按讚") ||
            t.contains("分享") || t.contains("留言") || t.contains("追蹤") || t.contains("訂閱") ||
            t.contains("主辦單位") || t.contains("贊助單位") || t.contains("查看更多")
        ) return true
        // Pure dates / times
        if (FULL_DATE_REGEX.containsMatchIn(t) || MONTH_DAY_REGEX.containsMatchIn(t)) return true
        if (t.startsWith("時間：") || t.startsWith("時間:") || t.startsWith("日期：") || t.startsWith("日期:")) return true
        if (t.startsWith("營業時間")) return true
        // Pure location
        if (t.startsWith("地點：") || t.startsWith("地點:") || t.startsWith("地址：") || t.startsWith("地址:")) return true
        if (isKnownVenue(t)) return true

        return false
    }

    /**
     * Conservative location extraction:
     * Identifies explicit location tags or recognized venue names/suffixes.
     */
    fun extractLocation(lines: List<String>): String? {
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("地點：") || t.startsWith("地點:") || t.startsWith("地點 ")) {
                val loc = t.substring(3).trim()
                if (loc.isNotEmpty()) return loc
            }
            if (t.startsWith("地址：") || t.startsWith("地址:") || t.startsWith("地址 ")) {
                val loc = t.substring(3).trim()
                if (loc.isNotEmpty()) return loc
            }
            if (isKnownVenue(t)) {
                return t
            }
        }
        return null
    }

    private fun isKnownVenue(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("流行音樂中心") || t.endsWith("音樂中心") || t.endsWith("文化中心") ||
            t.endsWith("展覽館") || t.endsWith("會議中心") || t.endsWith("小巨蛋") ||
            t.endsWith("大巨蛋") || t.endsWith("體育館") || t.endsWith("音樂廳") ||
            t.endsWith("演藝廳") || t.endsWith("文創園區")
        ) {
            return true
        }
        return false
    }

    private data class ParsedDateMatch(
        val startDate: LocalDate,
        val startTime: LocalTime?,
        val endTime: LocalTime?,
        val rawText: String,
        val patternTag: String,
        val contextSnippet: String,
        val lineIndex: Int,
        val role: DateRole = DateRole.EVENT_TIME
    )

    companion object {
        private val FULL_DATE_REGEX = Regex("""(?:(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})|(\d{4})年(\d{1,2})月(\d{1,2})[日號]?)""")
        private val MONTH_DAY_REGEX = Regex("""(?:(\d{1,2})[/.-](\d{1,2})|(\d{1,2})月(\d{1,2})[日號]?)""")

        private val RELATIVE_DATE_PATTERNS = listOf(
            Regex("""(?:今天|明天|後天)"""),
            Regex("""(?:下週|下星期|下禮拜)[一二三四五六日天]"""),
            Regex("""(?:本週|週|星期|禮拜)[一二三四五六日天]""")
        )

        private val TIME_RANGE_REGEX = Regex("""((?:[0-9]{1,2}:[0-9]{2}|(?:早上|上午|中午|下午|晚上|晚間|夜間)?\s*[0-9兩一二三四五六七八九十]+\s*點(?:\s*半|\s*[0-9]{1,2}\s*分?)?))\s*[-~至到–—]\s*((?:[0-9]{1,2}:[0-9]{2}|(?:早上|上午|中午|下午|晚上|晚間|夜間)?\s*[0-9兩一二三四五六七八九十]+\s*點(?:\s*半|\s*[0-9]{1,2}\s*分?)?))""")
        private val SINGLE_TIME_REGEX = Regex("""(?:(?:早上|上午|中午|下午|晚上|晚間|夜間)\s*[0-9]{1,2}:[0-9]{2}|[0-9]{1,2}:[0-9]{2}|(?:早上|上午|中午|下午|晚上|晚間|夜間)\s*[0-9兩一二三四五六七八九十]+\s*點(?:\s*半|\s*[0-9]{1,2}\s*分?)?)""")

        private val BUSINESS_HOURS_PATTERNS = listOf(
            Regex("""營業時間.*"""),
            Regex("""(?:週一至週五|星期一至五|平日).*""")
        )
    }
}
