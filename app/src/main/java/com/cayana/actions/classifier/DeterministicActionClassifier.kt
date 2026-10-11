package com.cayana.actions.classifier

import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.event.extractor.EventExtractor
import com.cayana.event.model.ConfidenceLevel
import com.cayana.event.model.DateRole
import com.cayana.event.model.EventActionPolicy
import com.cayana.memory.model.MemoryItem
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic, rule-based ActionClassifier.
 * Operates purely locally without LLM inference.
 */
class DeterministicActionClassifier(
    private val eventExtractor: EventExtractor
) : ActionClassifier {

    companion object {
        private val TASK_PREFIX_REGEX = Regex(
            """^(?:TODO[:：]?|Task[:：]?|待辦(?:事項)?[:：]?|待處理[:：]?|\[\s*\]\s*|-\s*\[\s*\]\s*|\*\s*\[\s*\]\s*)\s*""",
            RegexOption.IGNORE_CASE
        )

        private val TASK_IMPERATIVE_KEYWORDS = listOf(
            "記得", "提醒我", "需於", "務必", "請於", "要記得", "follow up", "remind me to"
        )

        private val TASK_DEADLINE_KEYWORDS = listOf(
            "截止", "到期", "期限", "報名截止", "收件截止", "due", "deadline"
        )

        private val UNCERTAINTY_KEYWORDS = listOf(
            "可能", "或許", "如果", "maybe", "probably", "perhaps", "?", "？"
        )

        private val NOTE_PREFIX_REGEX = Regex(
            """^(?:筆記[:：]?|備忘(?:錄)?[:：]?|會議記錄[:：]?|心得[:：]?|Note[:：]?|Memo[:：]?|Summary[:：]?|筆記內容[:：]?)\s*""",
            RegexOption.IGNORE_CASE
        )

        private val RECEIPT_KEYWORDS = listOf(
            "統一發票", "發票", "收據", "消費明細", "結帳明細", "總計", "實付金額", "Receipt", "Invoice", "Total"
        )
    }

    override fun classify(
        memoryItem: MemoryItem,
        rawText: String?,
        referenceTime: Instant,
        zoneId: ZoneId
    ): List<ActionCandidate> {
        val text = rawText?.trim()
        if (text.isNullOrEmpty()) {
            return emptyList()
        }

        val candidates = mutableListOf<ActionCandidate>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Evaluate Calendar Event
        val eventCandidates = eventExtractor.extract(text, referenceTime, zoneId)
        val eventTimeCandidate = eventCandidates.firstOrNull { it.dateRole == DateRole.EVENT_TIME }
        if (eventTimeCandidate != null && eventTimeCandidate.startAt != null) {
            val confidenceLevel = EventActionPolicy.evaluate(eventTimeCandidate, referenceTime)
            val title = eventTimeCandidate.title ?: memoryItem.title ?: lines.firstOrNull() ?: "Event"
            val startAt = eventTimeCandidate.startAt
            val endAt = eventTimeCandidate.endAt ?: startAt.plusSeconds(3600)

            val (confidence, requiresConfirmation) = when (confidenceLevel) {
                ConfidenceLevel.HIGH -> Pair(eventTimeCandidate.confidence, false)
                ConfidenceLevel.MEDIUM -> Pair(eventTimeCandidate.confidence, true)
                ConfidenceLevel.LOW -> Pair(minOf(eventTimeCandidate.confidence, 0.40f), true)
            }

            candidates.add(
                ActionCandidate(
                    type = ActionCandidateType.CALENDAR_EVENT,
                    title = title,
                    sourceMemoryId = memoryItem.id,
                    confidence = confidence,
                    startAt = startAt,
                    endAt = endAt,
                    location = eventTimeCandidate.location,
                    notes = text.take(200),
                    requiresConfirmation = requiresConfirmation
                )
            )
        }

        // 2. Evaluate Task
        val deadlineCandidate = eventCandidates.firstOrNull {
            it.dateRole in listOf(DateRole.DEADLINE, DateRole.REGISTRATION_DEADLINE)
        }
        val taskCandidate = classifyTask(lines, text, memoryItem, deadlineCandidate?.startAt)
        if (taskCandidate != null) {
            candidates.add(taskCandidate)
        }

        // 3. Evaluate Note
        val noteCandidate = classifyNote(lines, text, memoryItem)
        if (noteCandidate != null) {
            candidates.add(noteCandidate)
        }

        return candidates.sortedByDescending { it.confidence }
    }

    private fun classifyTask(
        lines: List<String>,
        fullText: String,
        memoryItem: MemoryItem,
        deadlineDueAt: Instant?
    ): ActionCandidate? {
        var matchedLine: String? = null
        var isExplicitPrefix = false
        var isImperative = false
        var isDeadlineKeyword = false

        for (line in lines) {
            if (TASK_PREFIX_REGEX.containsMatchIn(line)) {
                matchedLine = line
                isExplicitPrefix = true
                break
            }
            if (TASK_IMPERATIVE_KEYWORDS.any { line.contains(it, ignoreCase = true) }) {
                matchedLine = line
                isImperative = true
                break
            }
            if (TASK_DEADLINE_KEYWORDS.any { line.contains(it, ignoreCase = true) }) {
                matchedLine = line
                isDeadlineKeyword = true
                break
            }
        }

        if (matchedLine == null && deadlineDueAt == null) {
            return null
        }

        val targetLine = matchedLine ?: lines.firstOrNull() ?: memoryItem.title ?: "Task"
        val cleanTitle = TASK_PREFIX_REGEX.replace(targetLine, "").trim()
            .ifBlank { memoryItem.title ?: "Task" }

        val hasUncertainty = UNCERTAINTY_KEYWORDS.any { targetLine.contains(it, ignoreCase = true) }

        var confidence = when {
            isExplicitPrefix -> 0.90f
            isImperative -> 0.85f
            deadlineDueAt != null -> 0.80f
            isDeadlineKeyword -> 0.70f
            else -> 0.40f
        }

        if (hasUncertainty) {
            confidence -= 0.25f
        }
        if (cleanTitle.length < 3) {
            confidence -= 0.20f
        }

        confidence = confidence.coerceIn(0.10f, 0.95f)
        val requiresConfirmation = confidence < 0.80f || hasUncertainty

        return ActionCandidate(
            type = ActionCandidateType.TASK,
            title = cleanTitle,
            sourceMemoryId = memoryItem.id,
            confidence = confidence,
            dueAt = deadlineDueAt,
            notes = fullText.take(200),
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun classifyNote(
        lines: List<String>,
        fullText: String,
        memoryItem: MemoryItem
    ): ActionCandidate? {
        var isExplicitNote = false
        var isReceipt = false
        var noteTitle: String? = null

        for (line in lines) {
            if (NOTE_PREFIX_REGEX.containsMatchIn(line)) {
                isExplicitNote = true
                noteTitle = NOTE_PREFIX_REGEX.replace(line, "").trim()
                break
            }
            if (RECEIPT_KEYWORDS.any { line.contains(it, ignoreCase = true) }) {
                isReceipt = true
                noteTitle = line
                break
            }
        }

        if (!isExplicitNote && !isReceipt) {
            // General informative notes or memo
            val isMemo = lines.size >= 2 && lines.any { it.startsWith("-") || it.startsWith("*") || it.contains(":") || it.contains("：") }
            if (!isMemo) return null
        }

        val title = noteTitle?.ifBlank { null }
            ?: memoryItem.title
            ?: lines.firstOrNull()
            ?: "Note"

        var confidence = when {
            isExplicitNote -> 0.90f
            isReceipt -> 0.85f
            else -> 0.65f
        }

        confidence = confidence.coerceIn(0.10f, 0.95f)
        val requiresConfirmation = confidence < 0.80f

        return ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = title,
            sourceMemoryId = memoryItem.id,
            confidence = confidence,
            notes = fullText.take(200),
            requiresConfirmation = requiresConfirmation
        )
    }
}
