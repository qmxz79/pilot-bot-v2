package com.qmxz.pilotbotv2.memory

import org.json.JSONArray

/**
 * Extracts memories from the driver's own words — no form to fill in.
 *
 * v1 lesson: memory was manual profile fields, so conversation never taught
 * the companion anything. v2 runs this extractor on every user utterance
 * and stores what it finds via [MemoryStore].
 *
 * Two paths:
 * - [extract] is rule-based (Chinese sentence patterns), synchronous and free;
 *   it stays the always-on writer. Deliberately conservative: a missed memory
 *   is harmless, a wrong memory ("remembered" something the driver never said)
 *   breaks trust.
 * - [extractionPrompt] + [parseLlmResponse] form the wired LLM pass (called
 *   from CopilotViewModel after a turn completes, low temperature, fire-and-
 *   forget). It catches anything regex cannot ("下周要接孩子" implies a
 *   routine) but only stores items the model extracted verbatim from the
 *   utterance — never speculation.
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

    /** Prompt for the LLM extraction pass. The model must answer with a JSON array only. */
    fun extractionPrompt(userText: String): String {
        return "你是车载语音助手的记忆抽取器。下面是司机说的一句话。" +
            "从中抽取值得长期记住的信息，类别只能是 PREFERENCE（喜好）、FACT（事实）、ROUTINE（习惯）。" +
            "规则：只抽取司机明确说出的信息，禁止推测；text 用简短陈述（如\"家住青秀区\"），" +
            "长度不超过 40 字；最多 3 条。" +
            "只输出 JSON 数组，例如 [{\"kind\":\"PREFERENCE\",\"text\":\"喜欢听老歌\"}]；" +
            "没有可抽取的信息就输出 []。不要输出数组以外的任何文字。\n" +
            "司机说：" + userText
    }

    /**
     * Parse the LLM extraction reply. Tolerates markdown fences and any
     * prose around the JSON array; invalid kinds fall back to FACT; entries
     * without text or longer than 80 chars are dropped. Returns [] on any
     * parse failure — the rule path already ran, so failure costs nothing.
     */
    fun parseLlmResponse(text: String): List<MemoryItem> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val array = JSONArray(text.substring(start, end + 1))
            val out = ArrayList<MemoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val raw = obj.optString("text").trim()
                if (raw.isEmpty() || raw.length > 80) continue
                val kind = try {
                    MemoryKind.valueOf(obj.optString("kind", "FACT").uppercase())
                } catch (_: IllegalArgumentException) {
                    MemoryKind.FACT
                }
                out.add(MemoryStore.newItem(kind, raw))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
