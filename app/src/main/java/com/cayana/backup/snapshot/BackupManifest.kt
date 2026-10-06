package com.cayana.backup.snapshot

import org.json.JSONObject

/**
 * Metadata manifest for a Cayana backup snapshot, contained inside the encrypted archive.
 */
data class BackupManifest(
    val backupFormatVersion: Short,
    val createdAt: Long,
    val appVersion: String,
    val databaseSchemaVersion: Int,
    val snapshotId: String,
    val memoryCount: Int,
    val calendarActionCount: Int,
    val entryChecksums: Map<String, String> // filename -> sha256 hex
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("backupFormatVersion", backupFormatVersion.toInt())
        obj.put("createdAt", createdAt)
        obj.put("appVersion", appVersion)
        obj.put("databaseSchemaVersion", databaseSchemaVersion)
        obj.put("snapshotId", snapshotId)
        obj.put("memoryCount", memoryCount)
        obj.put("calendarActionCount", calendarActionCount)
        val checksumsObj = JSONObject()
        entryChecksums.forEach { (k, v) -> checksumsObj.put(k, v) }
        obj.put("entryChecksums", checksumsObj)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): BackupManifest {
            val checksums = mutableMapOf<String, String>()
            val checksumsObj = obj.optJSONObject("entryChecksums")
            if (checksumsObj != null) {
                val keys = checksumsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    checksums[key] = checksumsObj.getString(key)
                }
            }
            return BackupManifest(
                backupFormatVersion = obj.getInt("backupFormatVersion").toShort(),
                createdAt = obj.getLong("createdAt"),
                appVersion = obj.optString("appVersion", "0.1.0"),
                databaseSchemaVersion = obj.optInt("databaseSchemaVersion", 7),
                snapshotId = obj.getString("snapshotId"),
                memoryCount = obj.getInt("memoryCount"),
                calendarActionCount = obj.getInt("calendarActionCount"),
                entryChecksums = checksums
            )
        }
    }
}
