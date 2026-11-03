package com.qmxz.pilotbotv2.nav

/**
 * Voice navigation orders ("导航到市政府"), detected before the text
 * reaches the LLM so a driving order is acted on, not chatted about.
 *
 * Only explicit navigation verbs are orders. A bare "去市政府" stays
 * chat — while driving it is often just talk ("我明天去市政府"). And a
 * question-shaped utterance ("导航到市政府要多久") is left to the LLM:
 * it asks about a route, it does not order one.
 *
 * Pure logic, no Android dependency: safe to unit-test on the JVM.
 */
object NavCommandParser {

    /** A parsed navigation order. */
    data class NavCommand(val destination: String)

    /** Leading politeness / intent words stripped before verb matching. */
    private val leadIns = listOf("请帮我", "帮我", "麻烦", "请", "我要", "我想")

    /** Verbs, longest first: "导航到" must win over "导航". */
    private val verbs = listOf("导航到", "导航去", "带我去", "带我到", "开车去", "开车到", "导航")

    /** Words that turn an utterance into a question, not an order. */
    private val questionWords =
        listOf("多久", "多远", "怎么走", "怎么去", "路线", "哪条路", "堵不堵", "吗", "呢")

    /** Trailing particles stripped from the destination, repeatedly. */
    private val trailingParticles = listOf("一下", "去", "吧")

    fun parse(raw: String): NavCommand? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (questionWords.any { text.contains(it) }) return null
        for (lead in leadIns) {
            if (text.startsWith(lead)) {
                text = text.removePrefix(lead)
                break
            }
        }
        text = text.trim()
        for (verb in verbs) {
            if (text.startsWith(verb)) {
                var dest = text.removePrefix(verb).trim()
                // Trailing punctuation from typed input.
                dest = dest.trimEnd('，', '。', '！', '？', '、', ',', '.', '!', '?')
                var changed: Boolean
                do {
                    changed = false
                    for (particle in trailingParticles) {
                        if (dest.endsWith(particle) && dest.length > particle.length) {
                            dest = dest.removeSuffix(particle)
                            changed = true
                        }
                    }
                } while (changed)
                dest = dest.trim()
                if (dest.isEmpty()) return null
                return NavCommand(dest)
            }
        }
        return null
    }
}
