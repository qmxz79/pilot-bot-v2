package com.qmxz.pilotbotv2.llm

/**
 * Sentence splitting for streaming TTS.
 *
 * The pipeline speaks sentence by sentence (so barge-in stops within one
 * sentence). This splitter turns both complete replies ([split]) and live
 * token streams ([IncrementalSplitter]) into speakable sentences.
 *
 * A "sentence" ends at a Chinese or English terminator (。！？；.!?),
 * including any closing quotes/brackets that immediately follow it.
 * Text without a terminator is kept as a remainder, not discarded.
 */
object SentenceSplitter {

    private val terminators = setOf('。', '！', '？', '；', '!', '?', ';')
    private val closers = setOf('”', '’', '」', '』', ')', '）', '\"', '\'')
    private val markdownChars = setOf('*', '#', '`', '_', '~')
    private val urlPattern = Regex("https?://\\S+")

    /** Split a complete text into sentences (remainder included as the last item). */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            current.append(ch)
            if (ch in terminators) {
                // Swallow immediately-following closing quotes/brackets.
                var j = i + 1
                while (j < text.length && text[j] in closers) {
                    current.append(text[j])
                    j++
                }
                i = j
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) out.add(sentence)
                current.clear()
            } else {
                i++
            }
        }
        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) out.add(remainder)
        return out
    }

    /** Strip markdown, URLs and extra whitespace so TTS never reads symbols aloud. */
    fun sanitizeForSpeech(text: String): String {
        var out = text
        out = urlPattern.replace(out, "")
        val sb = StringBuilder()
        out.forEach { ch ->
            if (ch !in markdownChars) sb.append(ch)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Incremental twin of [SentenceSplitter.split] for SSE token streams.
 * [feed] returns only sentences that are complete; the trailing incomplete
 * fragment stays buffered until more tokens arrive or [flush] is called.
 */
class IncrementalSplitter {

    private val buffer = StringBuilder()

    fun feed(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        buffer.append(token)
        val text = buffer.toString()
        val parts = SentenceSplitter.split(text)
        if (parts.isEmpty()) return emptyList()
        val endsWithTerminator = text.isNotEmpty() &&
            (text.last() in setOf('。', '！', '？', '；', '!', '?', ';') ||
                text.last() in setOf('”', '’', '」', '』', ')', '）', '\"', '\''))
        return if (endsWithTerminator) {
            buffer.clear()
            parts
        } else {
            buffer.clear()
            buffer.append(parts.last())
            parts.dropLast(1)
        }
    }

    /** The buffered remainder, or null when nothing is left. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifEmpty { null }
    }
}
