package com.cayana.ask.model

/**
 * Bounded context pack assembled for LLM transmission.
 *
 * Enforces hard serialized UTF-8 byte boundary.
 */
data class ContextPack(
    val query: String,
    val items: List<ContextPackItem>,
    val totalSerializedBytes: Int,
    val isTruncatedByByteLimit: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Renders items as untrusted historical data blocks with explicit boundaries
     * to protect against prompt injection. Escapes closing untrusted_memory tags within snippets.
     */
    fun renderUntrustedMemories(): String {
        if (items.isEmpty()) return ""
        return buildString {
            for (item in items) {
                append("<untrusted_memory id=\"").append(item.memoryId).append("\" ")
                append("source=\"").append(item.sourceType.name).append("\" ")
                append("captured=\"").append(item.capturedDateIso).append("\">\n")
                // Escape premature closing tags inside user content
                val safeSnippet = item.snippet
                    .replace("</untrusted_memory>", "&lt;/untrusted_memory&gt;")
                    .replace("<untrusted_memory", "&lt;untrusted_memory")
                append(safeSnippet).append("\n")
                append("</untrusted_memory>\n")
            }
        }.trimEnd()
    }
}
