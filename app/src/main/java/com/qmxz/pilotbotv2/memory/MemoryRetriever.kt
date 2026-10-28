package com.qmxz.pilotbotv2.memory

/**
 * Picks the memories relevant to the current user utterance, for injection
 * into the system prompt.
 *
 * Deliberately simple and deterministic: keyword/character overlap scoring,
 * no embeddings, no network. Chinese has no word boundaries, so scoring is
 * character-based: a memory scores for every distinct query character it
 * contains, plus a bonus when any two-character query substring appears in
 * it. Top-k with a score above zero wins. Semantic retrieval (embeddings)
 * is a future upgrade, not a v2 requirement — the prompt stays small and
 * the behaviour stays explainable.
 */
object MemoryRetriever {

    fun topK(query: String, memories: List<MemoryItem>, k: Int = 3): List<MemoryItem> {
        if (query.isBlank() || memories.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryChars = normalizedQuery.toSet()
        val bigrams = normalizedQuery.windowed(2).toSet()
        return memories
            .map { item ->
                val text = normalize(item.text)
                var score = 0
                queryChars.forEach { ch ->
                    if (text.contains(ch)) score += 1
                }
                bigrams.forEach { bigram ->
                    if (text.contains(bigram)) score += 2
                }
                item to score
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(k)
            .map { (item, _) -> item }
    }

    private fun normalize(text: String): String {
        return text.lowercase().filter { ch ->
            ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF
        }
    }
}
