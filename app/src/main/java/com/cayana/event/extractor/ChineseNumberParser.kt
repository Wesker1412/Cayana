package com.cayana.event.extractor

/**
 * Utility to convert Chinese number characters commonly found in dates and times.
 */
object ChineseNumberParser {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '貳' to 2, '兩' to 2, '俩' to 2,
        '三' to 3, '參' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陸' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9
    )

    /**
     * Converts a small Chinese number string (1..99) to Int.
     * E.g. "二" -> 2, "十" -> 10, "十一" -> 11, "二十五" -> 25, "三十" -> 30.
     */
    fun parseChineseInt(str: String): Int? {
        val trimmed = str.trim()
        if (trimmed.isEmpty()) return null

        // Try direct integer parse first
        trimmed.toIntOrNull()?.let { return it }

        var total = 0
        var currentDigit = 0
        var hasTen = false

        for (ch in trimmed) {
            val d = DIGITS[ch]
            if (d != null) {
                currentDigit = d
            } else if (ch == '十' || ch == '拾') {
                hasTen = true
                total += if (currentDigit == 0) 10 else currentDigit * 10
                currentDigit = 0
            } else {
                return null
            }
        }
        total += currentDigit
        return if (total > 0 || hasTen || trimmed == "零") total else null
    }

    /**
     * Maps Chinese day of week word to java.time.DayOfWeek value (1..7).
     * E.g. "一" -> 1 (Monday), "五" -> 5 (Friday), "六" -> 6 (Saturday), "日" / "天" -> 7 (Sunday).
     */
    fun parseDayOfWeek(char: Char): Int? {
        return when (char) {
            '一' -> 1
            '二' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '日', '天' -> 7
            else -> null
        }
    }
}
