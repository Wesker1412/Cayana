package com.cayana.backup.drive

import com.cayana.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class GoogleDriveBackupClientTest {

    private lateinit var fakeDriveClient: FakeGoogleDriveBackupClient

    @Before
    fun setUp() {
        fakeDriveClient = FakeGoogleDriveBackupClient()
    }

    @Test
    fun uploadSuccessfulSnapshot() = runTest {
        val payload = "encrypted-bytes-snapshot-1".toByteArray()
        val fileName = "cayana-v1-snapshot-1.cynb"

        val result = fakeDriveClient.uploadBackup(fileName, payload)
        assertTrue(result is Result.Success)

        val metadata = (result as Result.Success).data
        assertEquals(fileName, metadata.fileName)
        assertEquals(payload.size.toLong(), metadata.sizeBytes)

        val listResult = fakeDriveClient.listBackups()
        assertTrue(listResult is Result.Success)
        val list = (listResult as Result.Success).data
        assertEquals(1, list.size)
        assertEquals(metadata.fileId, list.first().fileId)
    }

    @Test
    fun failedUploadPreservesPreviousGoodBackup() = runTest {
        // Upload 1st good backup
        val payload1 = "good-backup-1".toByteArray()
        val upload1 = fakeDriveClient.uploadBackup("cayana-v1-snap-1.cynb", payload1)
        assertTrue(upload1 is Result.Success)

        // Simulate upload failure for 2nd backup
        fakeDriveClient.uploadFailure = IOException("Connection reset by peer")
        val upload2 = fakeDriveClient.uploadBackup("cayana-v1-snap-2.cynb", "bad-backup-2".toByteArray())
        assertTrue(upload2 is Result.Error)

        // Reset failure and verify 1st backup is untouched and intact
        fakeDriveClient.uploadFailure = null
        val listResult = fakeDriveClient.listBackups()
        assertTrue(listResult is Result.Success)
        val backups = (listResult as Result.Success).data
        assertEquals(1, backups.size)
        assertEquals("cayana-v1-snap-1.cynb", backups.first().fileName)

        val downloaded = fakeDriveClient.downloadBackup(backups.first().fileId)
        assertTrue(downloaded is Result.Success)
        assertTrue((downloaded as Result.Success).data.contentEquals(payload1))
    }

    @Test
    fun retentionKeepsLatestThree() = runTest {
        // Simulate uploading 5 sequential backups and pruning older than latest 3
        val fileIds = mutableListOf<String>()
        for (i in 1..5) {
            val res = fakeDriveClient.uploadBackup("cayana-v1-snap-$i.cynb", "data-$i".toByteArray())
            assertTrue(res is Result.Success)
            fileIds.add((res as Result.Success).data.fileId)
            // ensure distinct timestamps
            Thread.sleep(10)
        }

        val allBeforePrune = (fakeDriveClient.listBackups() as Result.Success).data
        assertEquals(5, allBeforePrune.size)

        // Prune older than latest 3
        if (allBeforePrune.size > 3) {
            val toDelete = allBeforePrune.drop(3)
            for (item in toDelete) {
                val delRes = fakeDriveClient.deleteBackup(item.fileId)
                assertTrue(delRes is Result.Success)
            }
        }

        val afterPrune = (fakeDriveClient.listBackups() as Result.Success).data
        assertEquals(3, afterPrune.size)
        // Latest 3 should be snap-5, snap-4, snap-3
        assertEquals("cayana-v1-snap-5.cynb", afterPrune[0].fileName)
        assertEquals("cayana-v1-snap-4.cynb", afterPrune[1].fileName)
        assertEquals("cayana-v1-snap-3.cynb", afterPrune[2].fileName)
    }

    @Test
    fun resumableUploadResumesAfterInterruption() = runTest {
        fakeDriveClient.simulateInterruptedResumableUpload = true

        val fileName = "cayana-v1-large-snapshot.cynb"
        val data = ByteArray(6 * 1024 * 1024) { 0x42 } // 6 MB > 5 MB threshold

        // 1st attempt fails with interruption
        val firstAttempt = fakeDriveClient.uploadBackup(fileName, data)
        assertTrue(firstAttempt is Result.Error)

        // 2nd attempt succeeds resuming / completing
        val secondAttempt = fakeDriveClient.uploadBackup(fileName, data)
        assertTrue(secondAttempt is Result.Success)

        val fileMetadata = (secondAttempt as Result.Success).data
        assertEquals(data.size.toLong(), fileMetadata.sizeBytes)
        val stored = fakeDriveClient.getStoredContent(fileMetadata.fileId)
        assertNotNull(stored)
        assertEquals(data.size, stored?.size)
    }
}
