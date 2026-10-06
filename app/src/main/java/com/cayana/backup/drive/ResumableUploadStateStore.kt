package com.cayana.backup.drive

import org.json.JSONObject
import java.io.File

data class ResumableUploadState(
    val snapshotId: String,
    val encryptedTempFilePath: String,
    val sessionUri: String,
    val totalBytes: Long,
    val confirmedBytes: Long,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("snapshotId", snapshotId)
            put("encryptedTempFilePath", encryptedTempFilePath)
            put("sessionUri", sessionUri)
            put("totalBytes", totalBytes)
            put("confirmedBytes", confirmedBytes)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ResumableUploadState {
            return ResumableUploadState(
                snapshotId = json.getString("snapshotId"),
                encryptedTempFilePath = json.getString("encryptedTempFilePath"),
                sessionUri = json.getString("sessionUri"),
                totalBytes = json.getLong("totalBytes"),
                confirmedBytes = json.getLong("confirmedBytes"),
                createdAt = json.getLong("createdAt")
            )
        }
    }
}

interface ResumableUploadStateStore {
    fun saveState(state: ResumableUploadState)
    fun loadState(): ResumableUploadState?
    fun clearState()
}

class FileResumableUploadStateStore(
    private val directory: File
) : ResumableUploadStateStore {
    private val stateFile = File(directory, "resumable_upload_state.json")

    @Synchronized
    override fun saveState(state: ResumableUploadState) {
        try {
            directory.mkdirs()
            stateFile.writeText(state.toJson().toString())
        } catch (_: Exception) {}
    }

    @Synchronized
    override fun loadState(): ResumableUploadState? {
        return try {
            if (stateFile.exists()) {
                val json = JSONObject(stateFile.readText())
                ResumableUploadState.fromJson(json)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun clearState() {
        try {
            if (stateFile.exists()) {
                stateFile.delete()
            }
        } catch (_: Exception) {}
    }
}
