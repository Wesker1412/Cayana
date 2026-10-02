package com.cayana.memory.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cayana.memory.model.EventCandidate
import com.cayana.memory.model.MemoryItem
import com.cayana.processing.ProcessingState
import com.cayana.source.SourceType

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val sourceType: String,
    val createdAt: Long,
    val capturedAt: Long,
    val title: String?,
    val rawText: String?,
    val normalizedText: String?,
    val sourceUri: String?,
    val sourceUrl: String?,
    val sourceExists: Boolean,
    val metadataJson: String,
    val entitiesJson: String,
    val eventCandidatesJson: String,
    val processingState: String
) {
    fun toDomain(): MemoryItem {
        return MemoryItem(
            id = id,
            sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.SCREENSHOT),
            createdAt = createdAt,
            capturedAt = capturedAt,
            title = title,
            rawText = rawText,
            normalizedText = normalizedText,
            sourceUri = sourceUri,
            sourceUrl = sourceUrl,
            sourceExists = sourceExists,
            metadata = Converters.parseMetadata(metadataJson),
            entities = Converters.parseEntities(entitiesJson),
            eventCandidates = Converters.parseCandidates(eventCandidatesJson),
            processingState = runCatching { ProcessingState.valueOf(processingState) }.getOrDefault(ProcessingState.COMPLETED)
        )
    }

    companion object {
        fun fromDomain(item: MemoryItem): MemoryEntity {
            return MemoryEntity(
                id = item.id,
                sourceType = item.sourceType.name,
                createdAt = item.createdAt,
                capturedAt = item.capturedAt,
                title = item.title,
                rawText = item.rawText,
                normalizedText = item.normalizedText,
                sourceUri = item.sourceUri,
                sourceUrl = item.sourceUrl,
                sourceExists = item.sourceExists,
                metadataJson = Converters.serializeMetadata(item.metadata),
                entitiesJson = Converters.serializeEntities(item.entities),
                eventCandidatesJson = Converters.serializeCandidates(item.eventCandidates),
                processingState = item.processingState.name
            )
        }
    }
}
