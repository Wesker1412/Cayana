package com.cayana.cloud.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CloudMemoryPayloadV1(
    val id: String,
    val sourceType: String,
    val createdAt: Long,
    val capturedAt: Long,
    val title: String? = null,
    val rawText: String? = null,
    val normalizedText: String? = null,
    val sourceUri: String? = null,
    val sourceUrl: String? = null,
    val sourceExists: Boolean = false,
    val metadataJson: String = "{}",
    val entitiesJson: String = "[]",
    val eventCandidatesJson: String = "[]",
    val processingState: String = "COMPLETED"
) {
    fun toJson(): String = jsonFormat.encodeToString(this)

    companion object {
        private val jsonFormat = Json { ignoreUnknownKeys = true }
        fun fromJson(json: String): CloudMemoryPayloadV1 = jsonFormat.decodeFromString(json)
    }
}

@Serializable
data class CloudTombstonePayloadV1(
    val memoryId: String,
    val revision: Long,
    val deleted: Boolean = true
) {
    fun toJson(): String = jsonFormat.encodeToString(this)

    companion object {
        private val jsonFormat = Json { ignoreUnknownKeys = true }
        fun fromJson(json: String): CloudTombstonePayloadV1 = jsonFormat.decodeFromString(json)
    }
}

data class EncryptedCloudRecord(
    val memoryId: String,
    val revision: Long,
    val payloadVersion: Int,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val isTombstone: Boolean
)

fun CloudMemoryPayloadV1.toDomain(): com.cayana.memory.model.MemoryItem {
    return com.cayana.memory.model.MemoryItem(
        id = id,
        sourceType = runCatching { com.cayana.source.SourceType.valueOf(sourceType) }.getOrDefault(com.cayana.source.SourceType.SCREENSHOT),
        createdAt = createdAt,
        capturedAt = capturedAt,
        title = title,
        rawText = rawText,
        normalizedText = normalizedText,
        sourceUri = sourceUri,
        sourceUrl = sourceUrl,
        sourceExists = sourceExists,
        metadata = com.cayana.memory.data.Converters.parseMetadata(metadataJson),
        entities = com.cayana.memory.data.Converters.parseEntities(entitiesJson),
        eventCandidates = com.cayana.memory.data.Converters.parseCandidates(eventCandidatesJson),
        processingState = runCatching { com.cayana.processing.ProcessingState.valueOf(processingState) }.getOrDefault(com.cayana.processing.ProcessingState.COMPLETED)
    )
}

fun com.cayana.memory.model.MemoryItem.toCloudPayload(): CloudMemoryPayloadV1 {
    return CloudMemoryPayloadV1(
        id = id,
        sourceType = sourceType.name,
        createdAt = createdAt,
        capturedAt = capturedAt,
        title = title,
        rawText = rawText,
        normalizedText = normalizedText,
        sourceUri = sourceUri,
        sourceUrl = sourceUrl,
        sourceExists = sourceExists,
        metadataJson = com.cayana.memory.data.Converters.serializeMetadata(metadata),
        entitiesJson = com.cayana.memory.data.Converters.serializeEntities(entities),
        eventCandidatesJson = com.cayana.memory.data.Converters.serializeCandidates(eventCandidates),
        processingState = processingState.name
    )
}

