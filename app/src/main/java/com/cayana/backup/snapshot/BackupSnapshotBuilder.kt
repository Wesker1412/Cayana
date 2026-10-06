package com.cayana.backup.snapshot

import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.memory.data.Converters
import com.cayana.memory.model.MemoryItem
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupSnapshotBuilder {

    fun buildArchive(
        snapshotId: String,
        memories: List<MemoryItem>,
        calendarActions: List<CalendarActionEntity>,
        portableSettings: PortableUserSettings,
        appVersion: String = "0.1.0",
        schemaVersion: Int = 7
    ): ByteArray {
        return buildArchiveWithHistory(
            snapshotId = snapshotId,
            memories = memories,
            calendarActions = calendarActions.map { PortableCalendarActionHistory.fromLiveAction(it) },
            portableSettings = portableSettings,
            appVersion = appVersion,
            schemaVersion = schemaVersion
        )
    }

    fun buildArchiveWithHistory(
        snapshotId: String,
        memories: List<MemoryItem>,
        calendarActions: List<PortableCalendarActionHistory>,
        portableSettings: PortableUserSettings,
        appVersion: String = "0.1.0",
        schemaVersion: Int = 7
    ): ByteArray {
        val memoriesBytes = buildMemoriesJsonl(memories)
        val actionsBytes = buildCalendarActionsJsonl(calendarActions)
        val settingsBytes = portableSettings.toJson().toString().toByteArray(Charsets.UTF_8)

        val entryChecksums = mapOf(
            "memories.jsonl" to computeSha256Hex(memoriesBytes),
            "calendar_action_history.jsonl" to computeSha256Hex(actionsBytes),
            "settings.json" to computeSha256Hex(settingsBytes)
        )

        val manifest = BackupManifest(
            backupFormatVersion = 1,
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            databaseSchemaVersion = schemaVersion,
            snapshotId = snapshotId,
            memoryCount = memories.size,
            calendarActionCount = calendarActions.size,
            entryChecksums = entryChecksums
        )
        val manifestBytes = manifest.toJson().toString().toByteArray(Charsets.UTF_8)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            writeZipEntry(zos, "manifest.json", manifestBytes)
            writeZipEntry(zos, "memories.jsonl", memoriesBytes)
            writeZipEntry(zos, "calendar_action_history.jsonl", actionsBytes)
            writeZipEntry(zos, "settings.json", settingsBytes)
        }
        return baos.toByteArray()
    }

    private fun writeZipEntry(zos: ZipOutputStream, name: String, content: ByteArray) {
        val entry = ZipEntry(name)
        zos.putNextEntry(entry)
        zos.write(content)
        zos.closeEntry()
    }

    private fun buildMemoriesJsonl(memories: List<MemoryItem>): ByteArray {
        val sb = StringBuilder()
        for (memory in memories) {
            val obj = JSONObject()
            obj.put("id", memory.id)
            obj.put("sourceType", memory.sourceType.name)
            obj.put("createdAt", memory.createdAt)
            obj.put("capturedAt", memory.capturedAt)
            obj.put("title", memory.title)
            obj.put("rawText", memory.rawText)
            obj.put("normalizedText", memory.normalizedText)
            obj.put("sourceUri", memory.sourceUri)
            obj.put("sourceUrl", memory.sourceUrl)
            obj.put("sourceExists", memory.sourceExists)
            obj.put("metadata", JSONObject(memory.metadata))
            val entitiesArray = JSONArray()
            memory.entities.forEach { entitiesArray.put(it) }
            obj.put("entities", entitiesArray)
            obj.put("eventCandidates", JSONArray(Converters.serializeCandidates(memory.eventCandidates)))
            obj.put("processingState", memory.processingState.name)

            sb.append(obj.toString())
            sb.append("\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildCalendarActionsJsonl(actions: List<PortableCalendarActionHistory>): ByteArray {
        val sb = StringBuilder()
        for (action in actions) {
            val obj = JSONObject()
            obj.put("originalActionId", action.originalActionId)
            obj.put("id", action.originalActionId)
            obj.put("memoryId", action.memoryId)
            obj.put("calendarId", action.calendarId)
            if (action.calendarEventId != null) {
                obj.put("calendarEventId", action.calendarEventId)
            }
            obj.put("actionType", action.actionType)
            obj.put("createdAt", action.createdAt)
            obj.put("status", action.status)
            obj.put("title", action.title)
            if (action.startAt != null) obj.put("startAt", action.startAt)
            if (action.endAt != null) obj.put("endAt", action.endAt)
            obj.put("location", action.location)
            obj.put("isAllDay", action.isAllDay)
            obj.put("zoneId", action.zoneId)

            sb.append(obj.toString())
            sb.append("\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
