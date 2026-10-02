package com.cayana.processing

interface EntityExtractor {
    suspend fun extractEntities(text: String): List<String>
}

class DefaultEntityExtractor : EntityExtractor {
    override suspend fun extractEntities(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // Basic tokenization / entity extraction stub for Stage 0
        return text.split("\\s+".toRegex())
            .filter { it.length > 3 }
            .take(5)
    }
}
