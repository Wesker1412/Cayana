package com.cayana.source.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClassifierTest {

    @Test
    fun api31IsRecordingTrueClassifiedAsRecording() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Recordings/",
            displayName = "Voice_001.m4a",
            mimeType = "audio/mp4",
            isRecordingColumn = true
        )
        assertTrue(result)
    }

    @Test
    fun isMusicTrueExcludedEvenIfRecordingFlagSet() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Music/",
            displayName = "Song.mp3",
            mimeType = "audio/mpeg",
            isRecordingColumn = true,
            isMusic = true
        )
        assertFalse(result)
    }

    @Test
    fun isPodcastTrueExcluded() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Podcasts/",
            displayName = "Episode_1.m4a",
            mimeType = "audio/mp4",
            isPodcast = true
        )
        assertFalse(result)
    }

    @Test
    fun isRingtoneTrueExcluded() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Ringtones/",
            displayName = "Tone.ogg",
            mimeType = "audio/ogg",
            isRingtone = true
        )
        assertFalse(result)
    }

    @Test
    fun isNotificationTrueExcluded() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Notifications/",
            displayName = "Ping.ogg",
            mimeType = "audio/ogg",
            isNotification = true
        )
        assertFalse(result)
    }

    @Test
    fun isAlarmTrueExcluded() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Alarms/",
            displayName = "Beep.ogg",
            mimeType = "audio/ogg",
            isAlarm = true
        )
        assertFalse(result)
    }

    @Test
    fun legacyRecordingsPathClassifiedAsRecording() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Recordings/",
            displayName = "Voice_001.m4a",
            mimeType = "audio/mp4",
            isRecordingColumn = null
        )
        assertTrue(result)
    }

    @Test
    fun legacyRecorderPathClassifiedAsRecording() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Recorder/",
            displayName = "Meeting_notes.m4a",
            mimeType = "audio/mp4",
            isRecordingColumn = null
        )
        assertTrue(result)
    }

    @Test
    fun legacyVoiceRecorderPathClassifiedAsRecording() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Voice Recorder/",
            displayName = "memo.aac",
            mimeType = "audio/aac",
            isRecordingColumn = null
        )
        assertTrue(result)
    }

    @Test
    fun recorderPrefixNameClassifiedAsRecording() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Audio/",
            displayName = "recording_20261005_120000.m4a",
            mimeType = "audio/mp4",
            isRecordingColumn = null
        )
        assertTrue(result)
    }

    @Test
    fun musicDirectoryExplicitlyExcluded() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Music/",
            displayName = "song.mp3",
            mimeType = "audio/mpeg",
            isRecordingColumn = null
        )
        assertFalse(result)
    }

    @Test
    fun ambiguousAudioFileInRootDirectoryIsSkipped() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Download/",
            displayName = "random_audio_track.mp3",
            mimeType = "audio/mpeg",
            isRecordingColumn = null
        )
        assertFalse(result)
    }

    @Test
    fun nonAudioMimeIsRejected() {
        val result = RecordingClassifier.isRecording(
            relativePath = "Recordings/",
            displayName = "notes.txt",
            mimeType = "text/plain",
            isRecordingColumn = null
        )
        assertFalse(result)
    }
}
