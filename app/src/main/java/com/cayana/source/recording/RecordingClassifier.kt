package com.cayana.source.recording

import com.cayana.source.SourceItem

/**
 * Classifies whether a MediaStore audio item is a genuine Voice/Audio Recording.
 * Strictly filters out Music, Podcasts, Ringtones, Alarms, and Notification sounds.
 * Prioritizes MediaStore.Audio.AudioColumns.IS_RECORDING on API 31+.
 * Uses conservative fallback for API <= 30.
 */
object RecordingClassifier {

    private val RECORDING_PATH_KEYWORDS = listOf(
        "recordings/",
        "recordings",
        "recorder/",
        "recorder",
        "voice recorder/",
        "voice_recorder/",
        "voice_recorder",
        "sound recorder/",
        "sound_recorder/",
        "sound_recorder",
        "audiorecorder/",
        "call_recordings/",
        "callrecordings/",
        "call_recorder/",
        "miui/sound_recorder",
        "voicerecorder",
        "com.google.android.apps.recorder"
    )

    private val RECORDING_NAME_PREFIXES = listOf(
        "recording_",
        "record_",
        "audio_",
        "voice_",
        "rec_",
        "sound_",
        "call_",
        "錄音",
        "語音",
        "通話"
    )

    fun isRecording(
        relativePath: String?,
        displayName: String?,
        mimeType: String?,
        isRecordingColumn: Boolean? = null,
        isMusic: Boolean = false,
        isPodcast: Boolean = false,
        isRingtone: Boolean = false,
        isNotification: Boolean = false,
        isAlarm: Boolean = false
    ): Boolean {
        // 1. MIME type validation: must be audio
        val cleanMime = mimeType?.lowercase()?.trim() ?: ""
        if (cleanMime.isNotBlank() && !cleanMime.startsWith("audio/")) {
            return false
        }

        // 2. Strict rejection of explicit non-recording audio categories
        if (isMusic || isPodcast || isRingtone || isNotification || isAlarm) {
            return false
        }

        // 3. API 31+ positive check: IS_RECORDING == 1
        if (isRecordingColumn == true) {
            return true
        }

        val path = relativePath?.lowercase()?.trim() ?: ""
        val name = displayName?.lowercase()?.trim() ?: ""

        // Reject if explicitly in a Music or Podcast directory
        if (path.contains("music/") || path.contains("music") ||
            path.contains("podcasts/") || path.contains("podcasts") ||
            path.contains("ringtones/") || path.contains("notifications/") ||
            path.contains("alarms/")) {
            return false
        }

        // 4. Legacy path-based heuristic (API <= 30 or when IS_RECORDING is not set)
        val matchesRecordingPath = RECORDING_PATH_KEYWORDS.any { keyword ->
            path.contains(keyword)
        }
        if (matchesRecordingPath) {
            return true
        }

        // 5. Name-based heuristic (conservative)
        val matchesRecordingName = RECORDING_NAME_PREFIXES.any { prefix ->
            name.startsWith(prefix)
        }
        if (matchesRecordingName) {
            return true
        }

        // 6. Ambiguous audio -> safe skip! Never ingest unknown audio library as recordings.
        return false
    }

    fun isRecording(item: SourceItem): Boolean {
        val relPath = item.metadata["relativePath"]
        val displayName = item.metadata["displayName"]
        val mimeType = item.metadata["mimeType"]
        val isRec = item.metadata["isRecording"]?.toBoolean()
        val isMusic = item.metadata["isMusic"]?.toBoolean() ?: false
        val isPodcast = item.metadata["isPodcast"]?.toBoolean() ?: false
        val isRingtone = item.metadata["isRingtone"]?.toBoolean() ?: false
        val isNotification = item.metadata["isNotification"]?.toBoolean() ?: false
        val isAlarm = item.metadata["isAlarm"]?.toBoolean() ?: false
        return isRecording(
            relativePath = relPath,
            displayName = displayName,
            mimeType = mimeType,
            isRecordingColumn = isRec,
            isMusic = isMusic,
            isPodcast = isPodcast,
            isRingtone = isRingtone,
            isNotification = isNotification,
            isAlarm = isAlarm
        )
    }
}
