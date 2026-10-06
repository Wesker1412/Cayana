package com.cayana.backup.snapshot

import com.cayana.calendar.data.CalendarActionEntity
import com.cayana.memory.model.EventCandidate
import com.cayana.memory.model.EventConfidence
import com.cayana.memory.model.MemoryItem
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSnapshotTest {

    private fun createSampleMemories(): List<MemoryItem> {
        val now = System.currentTimeMillis()
        val screenshotMem = MemoryItem(
            id = "mem-screenshot-1",
            sourceType = SourceType.SCREENSHOT,
            createdAt = now,
            capturedAt = now - 1000,
            title = "高鐵票取票證明",
            rawText = "台灣高鐵訂位代號 12345678 車次 0123 台北至左營 14:00",
            normalizedText = "台灣高鐵訂位代號 12345678 車次 0123 台北至左營 14:00",
            sourceUri = "content://media/external/images/media/100",
            sourceUrl = null,
            sourceExists = true,
            metadata = mapOf("width" to "1080", "height" to "2400"),
            entities = listOf("台灣高鐵", "台北", "左營"),
            eventCandidates = listOf(
                EventCandidate(
                    title = "高鐵 0123 車次",
                    startTimestamp = now + 86400000,
                    endTimestamp = now + 90000000,
                    location = "台北車站",
                    confidence = EventConfidence.HIGH
                )
            ),
            processingState = ProcessingState.COMPLETED
        )

        val photoMem = MemoryItem(
            id = "mem-photo-2",
            sourceType = SourceType.PHOTO,
            createdAt = now,
            capturedAt = now - 5000,
            title = "會議白板",
            rawText = "Q4 Product Strategy: Cayana v1 Release and Backup Engine",
            normalizedText = "Q4 Product Strategy: Cayana v1 Release and Backup Engine",
            sourceUri = "content://media/external/images/media/200",
            sourceUrl = null,
            sourceExists = true,
            metadata = mapOf("camera" to "Pixel 8"),
            entities = listOf("Cayana"),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )

        val recordingMem = MemoryItem(
            id = "mem-recording-3",
            sourceType = SourceType.RECORDING,
            createdAt = now,
            capturedAt = now - 10000,
            title = "語音速記",
            rawText = "明天下午三點跟團隊討論資料庫遷移架構",
            normalizedText = "明天下午三點跟團隊討論資料庫遷移架構",
            sourceUri = "content://media/external/audio/media/300",
            sourceUrl = null,
            sourceExists = true,
            metadata = mapOf("durationMs" to "45000"),
            entities = listOf("資料庫遷移"),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )

        val sharedMem = MemoryItem(
            id = "mem-share-4",
            sourceType = SourceType.SHARED_URL,
            createdAt = now,
            capturedAt = now - 2000,
            title = "GitHub Android Architecture Guide",
            rawText = "Google recommended Android app architecture with Jetpack Compose",
            normalizedText = "Google recommended Android app architecture with Jetpack Compose",
            sourceUri = null,
            sourceUrl = "https://developer.android.com/topic/architecture",
            sourceExists = false,
            metadata = mapOf("host" to "developer.android.com"),
            entities = listOf("Android", "Jetpack Compose"),
            eventCandidates = emptyList(),
            processingState = ProcessingState.COMPLETED
        )

        return listOf(screenshotMem, photoMem, recordingMem, sharedMem)
    }

    private fun createSampleCalendarActions(): List<CalendarActionEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            CalendarActionEntity(
                id = "action-1",
                memoryId = "mem-screenshot-1",
                calendarId = 1L,
                calendarEventId = 999L,
                actionType = "CREATE_EVENT",
                createdAt = now,
                status = "CONFIRMED",
                title = "高鐵 0123 車次",
                startAt = now + 86400000,
                endAt = now + 90000000,
                location = "台北車站",
                isAllDay = false,
                zoneId = "Asia/Taipei"
            )
        )
    }

    @Test
    fun snapshotContainsAllCanonicalMemoryTypes() {
        val snapshotId = UUID.randomUUID().toString()
        val memories = createSampleMemories()
        val actions = createSampleCalendarActions()
        val settings = PortableUserSettings(
            onboardingCompleted = true,
            enabledSources = setOf("SCREENSHOT", "PHOTO", "RECORDING", "SHARED_URL"),
            notificationsEnabled = true
        )

        val archive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = memories,
            calendarActions = actions,
            portableSettings = settings
        )

        val parsed = BackupSnapshotParser.parseAndValidate(archive)
        assertEquals(4, parsed.memories.size)
        val sourceTypes = parsed.memories.map { it.sourceType }.toSet()
        assertTrue(sourceTypes.contains(SourceType.SCREENSHOT))
        assertTrue(sourceTypes.contains(SourceType.PHOTO))
        assertTrue(sourceTypes.contains(SourceType.RECORDING))
        assertTrue(sourceTypes.contains(SourceType.SHARED_URL))
    }

    @Test
    fun snapshotContainsOcrAndTranscript() {
        val snapshotId = UUID.randomUUID().toString()
        val memories = createSampleMemories()
        val actions = createSampleCalendarActions()
        val settings = PortableUserSettings()

        val archive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = memories,
            calendarActions = actions,
            portableSettings = settings
        )

        val parsed = BackupSnapshotParser.parseAndValidate(archive)
        val ocrItem = parsed.memories.first { it.sourceType == SourceType.SCREENSHOT }
        assertTrue(ocrItem.rawText?.contains("台灣高鐵訂位代號") == true)

        val recordingItem = parsed.memories.first { it.sourceType == SourceType.RECORDING }
        assertTrue(recordingItem.rawText?.contains("語音速記") == true || recordingItem.rawText?.contains("明天下午三點") == true)
    }

    @Test
    fun snapshotContainsCalendarHistory() {
        val snapshotId = UUID.randomUUID().toString()
        val memories = createSampleMemories()
        val actions = createSampleCalendarActions()
        val settings = PortableUserSettings()

        val archive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = memories,
            calendarActions = actions,
            portableSettings = settings
        )

        val parsed = BackupSnapshotParser.parseAndValidate(archive)
        assertEquals(1, parsed.calendarActions.size)
        val historicalAction = parsed.calendarActions.first()
        assertEquals("action-1", historicalAction.originalActionId)
        assertEquals("mem-screenshot-1", historicalAction.memoryId)
        assertEquals("高鐵 0123 車次", historicalAction.title)
        assertEquals("台北車站", historicalAction.location)
    }

    @Test
    fun snapshotExcludesFtsAndDerivedEntries() {
        val snapshotId = UUID.randomUUID().toString()
        val archive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = createSampleMemories(),
            calendarActions = createSampleCalendarActions(),
            portableSettings = PortableUserSettings()
        )

        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Verify ONLY whitelisted logical files are in archive
        assertEquals(4, entryNames.size)
        assertTrue(entryNames.contains("manifest.json"))
        assertTrue(entryNames.contains("memories.jsonl"))
        assertTrue(entryNames.contains("calendar_action_history.jsonl"))
        assertTrue(entryNames.contains("settings.json"))

        assertFalse(entryNames.any { it.contains("fts") || it.contains("search") })
        assertFalse(entryNames.any { it.contains("share_receipt") })
        assertFalse(entryNames.any { it.contains("cursor") })
        assertFalse(entryNames.any { it.contains("model") })
        assertFalse(entryNames.any { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".wav") || it.endsWith(".db") })
    }

    @Test
    fun snapshotRejectsUnknownEntry() {
        val snapshotId = UUID.randomUUID().toString()
        val goodArchive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = createSampleMemories(),
            calendarActions = createSampleCalendarActions(),
            portableSettings = PortableUserSettings()
        )

        // Inject an unauthorized entry
        val tamperedBaos = ByteArrayOutputStream()
        ZipOutputStream(tamperedBaos).use { zos ->
            ZipInputStream(ByteArrayInputStream(goodArchive)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(zis.readBytes())
                    zos.closeEntry()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zos.putNextEntry(ZipEntry("unauthorized_exploit.sh"))
            zos.write("echo hacked".toByteArray())
            zos.closeEntry()
        }

        try {
            BackupSnapshotParser.parseAndValidate(tamperedBaos.toByteArray())
            fail("Expected BackupValidationException on unknown entry")
        } catch (e: BackupValidationException) {
            assertTrue(e.message?.contains("不允許") == true || e.message?.contains("未知") == true)
        }
    }

    @Test
    fun snapshotRejectsPathTraversalEntry() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../../../etc/passwd"))
            zos.write("root:x:0:0".toByteArray())
            zos.closeEntry()
        }

        try {
            BackupSnapshotParser.parseAndValidate(baos.toByteArray())
            fail("Expected BackupValidationException on path traversal")
        } catch (e: BackupValidationException) {
            assertTrue(e.message?.contains("不允許") == true || e.message?.contains("非法路徑") == true)
        }
    }

    @Test
    fun snapshotRejectsTamperedManifestChecksum() {
        val snapshotId = UUID.randomUUID().toString()
        val memories = createSampleMemories()
        val archive = BackupSnapshotBuilder.buildArchive(
            snapshotId = snapshotId,
            memories = memories,
            calendarActions = createSampleCalendarActions(),
            portableSettings = PortableUserSettings()
        )

        // Modify memories.jsonl without updating manifest checksum
        val tamperedBaos = ByteArrayOutputStream()
        ZipOutputStream(tamperedBaos).use { zos ->
            ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    var bytes = zis.readBytes()
                    if (entry.name == "memories.jsonl") {
                        bytes = "tampered content".toByteArray()
                    }
                    zos.write(bytes)
                    zos.closeEntry()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        try {
            BackupSnapshotParser.parseAndValidate(tamperedBaos.toByteArray())
            fail("Expected BackupValidationException on checksum mismatch")
        } catch (e: BackupValidationException) {
            assertTrue(e.message?.contains("校驗碼不符") == true || e.message?.contains("損壞") == true)
        }
    }

    @Test
    fun zipBombRejectedBeforeUnboundedAllocation() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("memories.jsonl"))
            // Write 52MB of zeroes in 64KB blocks. Highly compressible (takes only ~50KB in zip)
            val chunk = ByteArray(64 * 1024)
            val totalBlocks = (52 * 1024) / 64 // 52MB
            for (i in 0 until totalBlocks) {
                zos.write(chunk)
            }
            zos.closeEntry()
        }

        val zipBombBytes = baos.toByteArray()
        assertTrue("Compressed zip bomb should be small (< 500KB)", zipBombBytes.size < 500 * 1024)

        try {
            BackupSnapshotParser.parseAndValidate(zipBombBytes)
            fail("Expected BackupValidationException on zip bomb")
        } catch (e: BackupValidationException) {
            assertTrue(
                "Exception message must mention entry size limit, was: ${e.message}",
                e.message?.contains("超過限制") == true || e.message?.contains("超過安全限制") == true
            )
        }
    }
}
