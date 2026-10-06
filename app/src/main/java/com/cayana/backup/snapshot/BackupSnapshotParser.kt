package com.cayana.backup.snapshot

import com.cayana.backup.config.BackupConfig
import com.cayana.calendar.data.RestoredCalendarActionHistoryEntity
import com.cayana.memory.data.Converters
import com.cayana.memory.model.MemoryItem
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class BackupValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ParsedBackupData(
    val manifest: BackupManifest,
    val memories: List<MemoryItem>,
    val calendarActions: List<RestoredCalendarActionHistoryEntity>,
    val settings: PortableUserSettings
)

object BackupSnapshotParser {

    @Throws(BackupValidationException::class)
    fun parseAndValidate(archiveBytes: ByteArray): ParsedBackupData {
        if (archiveBytes.size > BackupConfig.MAX_DECRYPTED_BACKUP_BYTES) {
            throw BackupValidationException("備份檔案解密後大小超過限制（最大 ${BackupConfig.MAX_DECRYPTED_BACKUP_BYTES} 位元組）。")
        }

        val entries = mutableMapOf<String, ByteArray>()
        var totalExtractedBytes = 0L

        try {
            ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name

                    // Security check 1: Whitelist entry names only
                    if (name !in BackupConfig.ALLOWED_ARCHIVE_ENTRIES) {
                        throw BackupValidationException("備份包含不允許的檔案條目：$name")
                    }

                    // Security check 2: Prevent path traversal
                    if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                        throw BackupValidationException("備份條目包含非法路徑：$name")
                    }

                    // Security check 3: Duplicate entries check
                    if (entries.containsKey(name)) {
                        throw BackupValidationException("備份檔案條目重複：$name")
                    }

                    val entryOut = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytesRead = 0L
                    var bytesRead: Int
                    while (zis.read(buffer).also { bytesRead = it } != -1) {
                        entryBytesRead += bytesRead
                        totalExtractedBytes += bytesRead
                        if (entryBytesRead > BackupConfig.MAX_SINGLE_ENTRY_DECOMPRESSED_BYTES) {
                            throw BackupValidationException("備份單一條目解壓縮大小超過限制（最大 ${BackupConfig.MAX_SINGLE_ENTRY_DECOMPRESSED_BYTES} 位元組）。")
                        }
                        if (totalExtractedBytes > BackupConfig.MAX_DECRYPTED_BACKUP_BYTES) {
                            throw BackupValidationException("備份解壓縮大小超過安全限制。")
                        }
                        entryOut.write(buffer, 0, bytesRead)
                    }

                    entries[name] = entryOut.toByteArray()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: Exception) {
            throw BackupValidationException("無法解壓縮備份歸檔：${e.message}", e)
        }

        // Validate presence of required entries
        for (requiredEntry in BackupConfig.ALLOWED_ARCHIVE_ENTRIES) {
            if (!entries.containsKey(requiredEntry)) {
                throw BackupValidationException("備份缺少必要條目：$requiredEntry")
            }
        }

        // Parse and validate manifest.json
        val manifestBytes = entries[BackupConfig.ENTRY_MANIFEST]!!
        val manifest = try {
            val json = JSONObject(String(manifestBytes, Charsets.UTF_8))
            BackupManifest.fromJson(json)
        } catch (e: Exception) {
            throw BackupValidationException("無法解析備份資訊清單 (manifest.json)：${e.message}", e)
        }

        if (manifest.backupFormatVersion > BackupConfig.BACKUP_FORMAT_VERSION) {
            throw BackupValidationException("不支援的備份格式版本：${manifest.backupFormatVersion}")
        }
        if (manifest.memoryCount > BackupConfig.MAX_RECORD_COUNT) {
            throw BackupValidationException("備份 Memory 筆數超過上限：${manifest.memoryCount}")
        }
        if (manifest.calendarActionCount > BackupConfig.MAX_RECORD_COUNT) {
            throw BackupValidationException("備份 CalendarAction 筆數超過上限：${manifest.calendarActionCount}")
        }

        // Validate SHA-256 checksums of each entry against manifest
        for ((entryName, expectedChecksum) in manifest.entryChecksums) {
            val entryContent = entries[entryName]
                ?: throw BackupValidationException("資訊清單中註記的條目未在封裝中找到：$entryName")
            val actualChecksum = BackupSnapshotBuilder.computeSha256Hex(entryContent)
            if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                throw BackupValidationException("條目 $entryName 內容校驗碼不符，備份已損壞。")
            }
        }

        // Parse memories.jsonl
        val memoriesBytes = entries[BackupConfig.ENTRY_MEMORIES]!!
        val memories = parseMemoriesJsonl(memoriesBytes)
        if (memories.size != manifest.memoryCount) {
            throw BackupValidationException("實際讀取的 Memory 筆數 (${memories.size}) 與清單 (${manifest.memoryCount}) 不符。")
        }

        // Parse calendar_action_history.jsonl
        val calendarActionsBytes = entries[BackupConfig.ENTRY_CALENDAR_ACTIONS]!!
        val calendarActions = parseCalendarActionsJsonl(calendarActionsBytes)
        if (calendarActions.size != manifest.calendarActionCount) {
            throw BackupValidationException("實際讀取的日曆歷程筆數 (${calendarActions.size}) 與清單 (${manifest.calendarActionCount}) 不符。")
        }

        // Parse settings.json
        val settingsBytes = entries[BackupConfig.ENTRY_SETTINGS]!!
        val settings = try {
            val json = JSONObject(String(settingsBytes, Charsets.UTF_8))
            PortableUserSettings.fromJson(json)
        } catch (e: Exception) {
            throw BackupValidationException("無法解析備份設定 (settings.json)：${e.message}", e)
        }

        return ParsedBackupData(
            manifest = manifest,
            memories = memories,
            calendarActions = calendarActions,
            settings = settings
        )
    }

    private fun parseMemoriesJsonl(bytes: ByteArray): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        val text = String(bytes, Charsets.UTF_8)
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val obj = JSONObject(trimmed)
                val id = obj.getString("id")
                val sourceType = runCatching { SourceType.valueOf(obj.getString("sourceType")) }.getOrDefault(SourceType.SCREENSHOT)
                val createdAt = obj.getLong("createdAt")
                val capturedAt = obj.getLong("capturedAt")
                val title = if (obj.has("title") && !obj.isNull("title")) obj.getString("title") else null
                val rawText = if (obj.has("rawText") && !obj.isNull("rawText")) obj.getString("rawText") else null
                val normalizedText = if (obj.has("normalizedText") && !obj.isNull("normalizedText")) obj.getString("normalizedText") else null
                val sourceUri = if (obj.has("sourceUri") && !obj.isNull("sourceUri")) obj.getString("sourceUri") else null
                val sourceUrl = if (obj.has("sourceUrl") && !obj.isNull("sourceUrl")) obj.getString("sourceUrl") else null
                val sourceExists = obj.optBoolean("sourceExists", false)

                val metadataMap = mutableMapOf<String, String>()
                val metaObj = obj.optJSONObject("metadata")
                if (metaObj != null) {
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        metadataMap[k] = metaObj.getString(k)
                    }
                }

                val entitiesList = mutableListOf<String>()
                val entitiesArr = obj.optJSONArray("entities")
                if (entitiesArr != null) {
                    for (i in 0 until entitiesArr.length()) {
                        entitiesList.add(entitiesArr.getString(i))
                    }
                }

                val eventCandidates = if (obj.has("eventCandidates")) {
                    Converters.parseCandidates(obj.get("eventCandidates").toString())
                } else {
                    emptyList()
                }

                val processingState = runCatching {
                    ProcessingState.valueOf(obj.getString("processingState"))
                }.getOrDefault(ProcessingState.COMPLETED)

                list.add(
                    MemoryItem(
                        id = id,
                        sourceType = sourceType,
                        createdAt = createdAt,
                        capturedAt = capturedAt,
                        title = title,
                        rawText = rawText,
                        normalizedText = normalizedText,
                        sourceUri = sourceUri,
                        sourceUrl = sourceUrl,
                        sourceExists = sourceExists,
                        metadata = metadataMap,
                        entities = entitiesList,
                        eventCandidates = eventCandidates,
                        processingState = processingState
                    )
                )
            }
        }
        return list
    }

    private fun parseCalendarActionsJsonl(bytes: ByteArray): List<RestoredCalendarActionHistoryEntity> {
        val list = mutableListOf<RestoredCalendarActionHistoryEntity>()
        val text = String(bytes, Charsets.UTF_8)
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val obj = JSONObject(trimmed)
                val originalId = obj.getString("id")
                val memoryId = obj.getString("memoryId")
                val calendarId = obj.getLong("calendarId")
                val calendarEventId = if (obj.has("calendarEventId") && !obj.isNull("calendarEventId")) obj.getLong("calendarEventId") else null
                val actionType = obj.getString("actionType")
                val createdAt = obj.getLong("createdAt")
                val status = obj.getString("status")
                val title = if (obj.has("title") && !obj.isNull("title")) obj.getString("title") else null
                val startAt = if (obj.has("startAt") && !obj.isNull("startAt")) obj.getLong("startAt") else null
                val endAt = if (obj.has("endAt") && !obj.isNull("endAt")) obj.getLong("endAt") else null
                val location = if (obj.has("location") && !obj.isNull("location")) obj.getString("location") else null
                val isAllDay = obj.optBoolean("isAllDay", false)
                val zoneId = if (obj.has("zoneId") && !obj.isNull("zoneId")) obj.getString("zoneId") else null

                list.add(
                    RestoredCalendarActionHistoryEntity(
                        id = "restored_$originalId",
                        originalActionId = originalId,
                        memoryId = memoryId,
                        actionType = actionType,
                        originalStatus = status,
                        createdAt = createdAt,
                        title = title,
                        startAt = startAt,
                        endAt = endAt,
                        location = location,
                        isAllDay = isAllDay,
                        zoneId = zoneId,
                        originalCalendarId = calendarId,
                        originalCalendarEventId = calendarEventId,
                        restoredAt = System.currentTimeMillis()
                    )
                )
            }
        }
        return list
    }
}
