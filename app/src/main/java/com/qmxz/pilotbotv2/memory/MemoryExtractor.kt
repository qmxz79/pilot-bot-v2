package com.qmxz.pilotbotv2.memory

/**
 * Extracts memories from the driver's own words — no form to fill in.
 *
 * v1 lesson: memory was manual profile fields, so conversation never taught
 * the companion anything. v2 runs this extractor on every user utterance
 * and stores what it finds via [MemoryStore].
 *
 * Current implementation is rule-based (Chinese sentence patterns). It is
 * deliberately conservative: a missed memory is harmless, a wrong memory
 * ("remembered" something the driver never said) breaks trust. The
 * [extractViaLlm] hook is where a future LLM pass plugs in — it currently
 * returns nothing, so the rule path is the only writer until that lands.
 */
object MemoryExtractor {

    private data class Rule(val pattern: Regex, val kind: MemoryKind)

    private val rules = listOf(
        Rule(Regex("我喜欢(.{1,40})"), MemoryKind.PREFERENCE),
        Rule(Regex("我爱(.{1,40})"), MemoryKind.PREFERENCE),
        Rule(Regex("我讨厌(.{1,40})"), MemoryKind.PREFERENCE),
        Rule(Regex("我不喜欢(.{1,40})"), MemoryKind.PREFERENCE),
        Rule(Regex("每周(.{1,40})"), MemoryKind.ROUTINE),
        Rule(Regex("每天(.{1,40})"), MemoryKind.ROUTINE),
        Rule(Regex("我家住(.{1,40})"), MemoryKind.FACT),
        Rule(Regex("我住在(.{1,40})"), MemoryKind.FACT),
        Rule(Regex("公司在(.{1,40})"), MemoryKind.FACT),
        Rule(Regex("我常走(.{1,40})"), MemoryKind.ROUTINE),
        Rule(Regex("我一般走(.{1,40})"), MemoryKind.ROUTINE),
        Rule(Regex("我习惯(.{1,40})"), MemoryKind.ROUTINE)
    )

    private val trailingNoise = Regex("[。！？!?,，、；;\\s]+$")

    fun extract(userText: String): List<MemoryItem> {
        val found = ArrayList<MemoryItem>()
        val seen = HashSet<String>()
        rules.forEach { rule ->
            rule.pattern.find(userText)?.let { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                val cleaned = raw.replace(trailingNoise, "").trim()
                if (cleaned.isNotEmpty() && seen.add(cleaned)) {
                    found.add(MemoryStore.newItem(rule.kind, cleaned))
                }
            }
        }
        return found
    }

    /**
     * Hook for future LLM-based extraction (richer than regex, e.g. "下周要去
     * 接孩子" implies a routine). Until it is wired to [com.qmxz.pilotbotv2.llm.LlmClient],
     * it returns an empty list and the rule path remains the only writer.
     */
    fun extractViaLlm(userText: String): List<MemoryItem> {
        // TODO: call LlmClient with an extraction prompt, parse JSON items.
        return emptyList()
    }
}
