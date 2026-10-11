package com.cayana.ask.model

/**
 * Bounded context pack assembled for LLM prompt context packaging.
 *
 * NOTE ON BYTE BOUNDARIES:
 * The promptContextBytes measures solely the context prompt payload (user query + rendered untrusted memory blocks).
 * It does NOT include the system prompt, model parameters, or the outer HTTP JSON envelope / headers.
 * Stage 8B remote HTTP providers must independently validate the final full HTTP serialized wire bytes
 * before network transmission.
 */
data class ContextPack(
    val query: String,
    val items: List<ContextPackItem>,
    val promptContextBytes: Int,
    val isTruncatedByByteLimit: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Backwards-compatible alias for promptContextBytes.
     */
    val totalSerializedBytes: Int get() = promptContextBytes

    /**
     * Renders items as untrusted historical data blocks with explicit boundaries
     * to protect against prompt injection. Escapes closing untrusted_memory tags within snippets
     * and strictly escapes attribute values (id, source, captured).
     */
    fun renderUntrustedMemories(): String {
        if (items.isEmpty()) return ""
        return buildString {
            for (item in items) {
                val safeId = escapeXmlAttribute(item.memoryId)
                val safeSource = escapeXmlAttribute(item.sourceType.name)
                val safeCaptured = escapeXmlAttribute(item.capturedDateIso)

                append("<untrusted_memory id=\"").append(safeId).append("\" ")
                append("source=\"").append(safeSource).append("\" ")
                append("captured=\"").append(safeCaptured).append("\">\n")

                // Escape premature closing tags and opening tags inside user content
                val safeSnippet = item.snippet
                    .replace("</untrusted_memory>", "&lt;/untrusted_memory&gt;")
                    .replace("<untrusted_memory", "&lt;untrusted_memory")
                append(safeSnippet).append("\n")
                append("</untrusted_memory>\n")
            }
        }.trimEnd()
    }

    /**
     * Produces a human-readable inspection preview of the ContextPack for user review
     * in authorization confirmation dialogs or privacy review panels before external transmission.
     */
    fun toInspectionPreview(): String {
        return buildString {
            append("Query: ").append(query).append("\n")
            append("Item Count: ").append(items.size).append("\n")
            append("Prompt Context Bytes: ").append(promptContextBytes).append("\n")
            append("Truncated by Budget: ").append(isTruncatedByByteLimit).append("\n\n")
            items.forEachIndexed { index, item ->
                append("--- Memory [").append(index + 1).append("] ---\n")
                append("ID: ").append(item.memoryId).append("\n")
                append("Source: ").append(item.sourceType.displayName).append("\n")
                append("Captured: ").append(item.capturedDateIso).append("\n")
                append("Snippet:\n").append(item.snippet).append("\n\n")
            }
        }.trimEnd()
    }

    private fun escapeXmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\r", "")
            .replace("\n", "")
    }
}
