package com.cayana.retrieval

import com.cayana.search.MemorySearchDocumentBuilder
import com.cayana.source.SourceType
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Result of lightweight, deterministic natural language question analysis.
 */
data class AnalyzedQuery(
    val rawQuery: String,
    val contentTerms: List<String>,
    val sourceHint: SourceType?,
    val timeHint: TimeHint?,
    val timeTokens: List<String>,
    val isGenericOrEmpty: Boolean
)

/**
 * Lightweight deterministic analyzer that converts natural language user questions into
 * structured hints (content terms, source hints, time range constraints) without using external ML models.
 */
object RetrievalQueryAnalyzer {

    private val CHINESE_MONTH_MAP = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
        "七" to 7, "八" to 8, "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
    )

    private val CHINESE_MONTHS_LIST = listOf(
        "一月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "十一月", "十二月"
    )

    private val NOISE_REGEXES = listOf(
        Regex("請問一下[，, ]*"),
        Regex("請問[，, ]*"),
        Regex("一下[，, ]*"),
        Regex("可以幫我[，, ]*"),
        Regex("幫我看[，, ]*"),
        Regex("幫我查[，, ]*"),
        Regex("幫我[，, ]*"),
        Regex("找一下[，, ]*"),
        Regex("查一下[，, ]*"),
        Regex("找看看[，, ]*"),
        Regex("看下[，, ]*"),
        Regex("有沒有在[，, ]*"),
        Regex("有沒有[，, ]*"),
        Regex("是不是[，, ]*"),
        Regex("我有沒有[，, ]*"),
        Regex("我記得好像是?[，, ]*"),
        Regex("我記得[，, ]*"),
        Regex("記得[，, ]*"),
        Regex("之前截圖的那個[，, ]*"),
        Regex("之前存的[，, ]*"),
        Regex("之前記的[，, ]*"),
        Regex("之前的那個[，, ]*"),
        Regex("之前那個[，, ]*"),
        Regex("之前的?[，, ]*"),
        Regex("之前有[，, ]*"),
        Regex("之前[，, ]*"),
        Regex("我想找[，, ]*"),
        Regex("我想看[，, ]*"),
        Regex("我想知道[，, ]*"),
        Regex("我想查[，, ]*"),
        Regex("哪一個[，, ]*"),
        Regex("哪裡有[，, ]*"),
        Regex("有什麼[，, ]*"),
        Regex("有哪[，, ]*"),
        Regex("什麼時候[，, ]*"),
        Regex("談了什麼[，, ]*"),
        Regex("講了什麼[，, ]*"),
        Regex("說了什麼[，, ]*"),
        Regex("聊了什麼[，, ]*"),
        Regex("討論了什麼[，, ]*"),
        Regex("討論了[，, ]*"),
        Regex("寫了什麼[，, ]*"),
        Regex("做了什麼[，, ]*"),
        Regex("是什麼[，, ]*"),
        Regex("的東西[，, ]*"),
        Regex("的一?[場個次][，, ]*"),
        Regex("一[場個次份首][，, ]*"),
        Regex("我跟[，, ]*"),
        Regex("我的[，, ]*"),
        Regex("我們[，, ]*"),
        Regex("那個[，, ]*"),
        Regex("[嗎呢呀吧啊啦啦？?！!。.,，~^]")
    )

    fun analyze(query: RetrievalQuery): AnalyzedQuery {
        val raw = query.text.trim().take(RetrievalPolicy.MAX_RAW_QUERY_CHARS)
        if (raw.isBlank()) {
            return AnalyzedQuery(
                rawQuery = raw,
                contentTerms = emptyList(),
                sourceHint = null,
                timeHint = null,
                timeTokens = emptyList(),
                isGenericOrEmpty = true
            )
        }

        // 1. Detect Source Hint
        val sourceHint = query.options.filterSourceType ?: extractSourceHint(raw)

        // 2. Detect Time Hint & Time Tokens
        val (timeHint, timeTokens) = extractTimeHint(raw, query.options)

        // 3. Extract content terms by stripping noise words, time hints, and explicit source labels
        val cleaned = cleanNoise(raw, timeHint?.label)
        val contentTerms = extractTerms(cleaned).take(RetrievalPolicy.MAX_CONTENT_TERMS)

        val isGenericOrEmpty = contentTerms.isEmpty() && timeHint == null && sourceHint == null

        return AnalyzedQuery(
            rawQuery = raw,
            contentTerms = contentTerms,
            sourceHint = sourceHint,
            timeHint = timeHint,
            timeTokens = timeTokens,
            isGenericOrEmpty = isGenericOrEmpty
        )
    }

    private fun extractSourceHint(text: String): SourceType? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("截圖") || lower.contains("螢幕截圖") || lower.contains("screenshot") -> SourceType.SCREENSHOT
            lower.contains("照片") || lower.contains("相片") || lower.contains("相機") || lower.contains("photo") -> SourceType.PHOTO
            lower.contains("錄音") || lower.contains("語音") || lower.contains("錄音檔") || lower.contains("recording") || lower.contains("audio") -> SourceType.RECORDING
            lower.contains("網址") || lower.contains("網頁") || lower.contains("連結") || lower.contains("url") || lower.contains("link") -> SourceType.SHARED_URL
            lower.contains("pdf") || lower.contains("文件") -> SourceType.SHARED_DOCUMENT
            lower.contains("文字") -> SourceType.SHARED_TEXT
            lower.contains("圖片") || lower.contains("圖檔") -> SourceType.SHARED_IMAGE
            else -> null
        }
    }

    private fun extractTimeHint(text: String, options: RetrievalOptions): Pair<TimeHint?, List<String>> {
        if (options.timeHint != null) {
            return Pair(options.timeHint, listOf(options.timeHint.label))
        }

        val clock = options.clock
        val zoneId = options.zoneId
        val now = ZonedDateTime.now(clock.withZone(zoneId))
        val currentYear = now.year

        // Pattern A: e.g. "2026年10月", "2026年十月"
        val yearMonthRegex = Regex("(\\d{4})年\\s*(\\d{1,2}|[一二三四五六七八九十]{1,2})月")
        val ymMatch = yearMonthRegex.find(text)
        if (ymMatch != null) {
            val y = ymMatch.groupValues[1].toInt()
            val mStr = ymMatch.groupValues[2]
            val m = mStr.toIntOrNull() ?: CHINESE_MONTH_MAP[mStr]
            if (m != null && m in 1..12) {
                val start = LocalDate.of(y, m, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val lastDay = LocalDate.of(y, m, 1).lengthOfMonth()
                val end = LocalDate.of(y, m, lastDay).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
                val tokens = listOf("${y}年${m}月", "${m}月", zhMonth, "$y")
                return Pair(TimeHint(start, end, ymMatch.value), tokens)
            }
        }

        // Pattern B: e.g. "10月", "十月" (in current year)
        val monthOnlyRegex = Regex("(\\d{1,2}|[一二三四五六七八九十]{1,2})月")
        val mMatch = monthOnlyRegex.find(text)
        if (mMatch != null) {
            val mStr = mMatch.groupValues[1]
            val m = mStr.toIntOrNull() ?: CHINESE_MONTH_MAP[mStr]
            if (m != null && m in 1..12) {
                val start = LocalDate.of(currentYear, m, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val lastDay = LocalDate.of(currentYear, m, 1).lengthOfMonth()
                val end = LocalDate.of(currentYear, m, lastDay).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
                val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
                val tokens = listOf("${m}月", zhMonth, "${currentYear}年${m}月")
                return Pair(TimeHint(start, end, mMatch.value), tokens)
            }
        }

        // Pattern C: Relative time ("今年", "去年", "這個月", "本月", "上個月")
        if (text.contains("今年")) {
            val start = LocalDate.of(currentYear, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val lastDay = LocalDate.of(currentYear, 12, 31).lengthOfMonth()
            val end = LocalDate.of(currentYear, 12, 31).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
            return Pair(TimeHint(start, end, "今年"), listOf("$currentYear", "${currentYear}年"))
        }

        if (text.contains("去年")) {
            val lastY = currentYear - 1
            val start = LocalDate.of(lastY, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = LocalDate.of(lastY, 12, 31).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
            return Pair(TimeHint(start, end, "去年"), listOf("$lastY", "${lastY}年"))
        }

        if (text.contains("這個月") || text.contains("本月")) {
            val m = now.monthValue
            val start = LocalDate.of(currentYear, m, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val lastDay = LocalDate.of(currentYear, m, 1).lengthOfMonth()
            val end = LocalDate.of(currentYear, m, lastDay).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
            val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
            return Pair(TimeHint(start, end, "這個月"), listOf("${m}月", zhMonth))
        }

        if (text.contains("上個月")) {
            val lastM = now.minusMonths(1)
            val y = lastM.year
            val m = lastM.monthValue
            val start = LocalDate.of(y, m, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val lastDay = LocalDate.of(y, m, 1).lengthOfMonth()
            val end = LocalDate.of(y, m, lastDay).atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()
            val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
            return Pair(TimeHint(start, end, "上個月"), listOf("${m}月", zhMonth, "$y"))
        }

        if (text.contains("上週") || text.contains("上星期") || text.contains("上禮拜")) {
            val currentDayOfWeek = now.dayOfWeek.value
            val thisWeekMonday = now.toLocalDate().minusDays((currentDayOfWeek - 1).toLong())
            val lastWeekMonday = thisWeekMonday.minusDays(7)
            val lastWeekSunday = lastWeekMonday.plusDays(6)

            val start = lastWeekMonday.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = lastWeekSunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()

            val m = lastWeekMonday.monthValue
            val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
            val tokens = listOf("上週", "上星期", "上禮拜", "${m}月", zhMonth)

            val matchedLabel = when {
                text.contains("上週") -> "上週"
                text.contains("上星期") -> "上星期"
                else -> "上禮拜"
            }
            return Pair(TimeHint(start, end, matchedLabel), tokens)
        }

        if (text.contains("這週") || text.contains("本週") || text.contains("這星期") || text.contains("這禮拜")) {
            val currentDayOfWeek = now.dayOfWeek.value
            val thisWeekMonday = now.toLocalDate().minusDays((currentDayOfWeek - 1).toLong())
            val thisWeekSunday = thisWeekMonday.plusDays(6)

            val start = thisWeekMonday.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = thisWeekSunday.atTime(23, 59, 59, 999_000_000).atZone(zoneId).toInstant().toEpochMilli()

            val m = thisWeekMonday.monthValue
            val zhMonth = CHINESE_MONTHS_LIST.getOrNull(m - 1) ?: "${m}月"
            val tokens = listOf("這週", "本週", "${m}月", zhMonth)

            val matchedLabel = when {
                text.contains("這週") -> "這週"
                text.contains("本週") -> "本週"
                text.contains("這星期") -> "這星期"
                else -> "這禮拜"
            }
            return Pair(TimeHint(start, end, matchedLabel), tokens)
        }

        return Pair(null, emptyList())
    }

    private fun cleanNoise(text: String, timeLabel: String?): String {
        var current = text
        if (!timeLabel.isNullOrBlank()) {
            current = current.replace(timeLabel, " ")
        }
        for (pattern in NOISE_REGEXES) {
            current = pattern.replace(current, " ")
        }
        // Also strip common source terms if they were used conversationally
        val sourceKeywords = listOf("截圖", "照片", "相片", "錄音", "網址", "網頁")
        for (src in sourceKeywords) {
            if (current.contains(src) && current.length > src.length + 2) {
                current = current.replace(src, " ")
            }
        }
        return current.trim()
    }

    private fun extractTerms(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val terms = mutableListOf<String>()

        // Replace non-alphanumeric, non-CJK chars with space so punctuation acts as token delimiters
        val normalized = text.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), " ")
        val rawTokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in rawTokens) {
            var cleaned = token.trim()
            // Strip leading / trailing '的'
            cleaned = cleaned.removePrefix("的").removeSuffix("的").trim()
            if (cleaned.isBlank() || cleaned == "的") continue

            val isAllCjk = cleaned.all { MemorySearchDocumentBuilder.isCjk(it) }
            if (isAllCjk) {
                terms.add(cleaned)
                // If CJK term is >= 4 characters, extract subterms (halves and segments)
                // to support natural compound queries like "捷克城堡", "居酒屋牛舌", "星巴克發票"
                if (cleaned.length in 4..6) {
                    val mid = cleaned.length / 2
                    val firstHalf = cleaned.substring(0, mid)
                    val secondHalf = cleaned.substring(mid)
                    if (firstHalf.length >= 2) terms.add(firstHalf)
                    if (secondHalf.length >= 2) terms.add(secondHalf)
                    if (cleaned.length == 5) {
                        // For 5-char words like "居酒屋牛舌", "星巴克發票"
                        terms.add(cleaned.substring(0, 3))
                        terms.add(cleaned.substring(3))
                    }
                } else if (cleaned.length > 6) {
                    // For longer queries like "張經理交付時程" or "專案架構規格書"
                    terms.add(cleaned.take(3))
                    terms.add(cleaned.take(4))
                    terms.add(cleaned.takeLast(3))
                    terms.add(cleaned.takeLast(4))
                    for (i in 0 until cleaned.length - 1 step 2) {
                        val chunk = cleaned.substring(i, minOf(i + 2, cleaned.length))
                        if (chunk.length >= 2) terms.add(chunk)
                    }
                }
            } else {
                terms.add(cleaned)
            }
        }
        return terms.filter { it.isNotBlank() && it != "的" }.distinct()
    }
}
