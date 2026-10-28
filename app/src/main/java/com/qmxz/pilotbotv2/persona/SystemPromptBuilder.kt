package com.qmxz.pilotbotv2.persona

import com.qmxz.pilotbotv2.memory.MemoryItem
import com.qmxz.pilotbotv2.safety.DriveScene
import com.qmxz.pilotbotv2.safety.SpeechStyle

/**
 * Builds the system prompt for EVERY LLM turn from the same ingredients, in
 * the same order. This is the direct fix for v1's "人设混乱": the model can
 * only stay in character if the character is re-anchored on every turn,
 * together with what it remembers, where the driver is, and how fast the
 * driver is going.
 *
 * Rules baked into every prompt:
 * - Spoken Chinese, short sentences, no markdown, no emoji, no URLs — the
 *   reply is read aloud, not displayed as an article.
 * - Never claim to see the road, the screen, or the driver.
 * - Driving safety overrides banter, always.
 */
object SystemPromptBuilder {

    fun build(
        card: PersonaCard,
        memories: List<MemoryItem>,
        scene: DriveScene,
        style: SpeechStyle,
        navContext: String?
    ): String {
        val sb = StringBuilder()
        sb.append("你是${card.name}，${card.identity}\n")
        sb.append("性格：${card.personality}\n")
        if (card.catchphrases.isNotEmpty()) {
            sb.append("口头禅（偶尔用，不要每句都用）：${card.catchphrases.joinToString("、")}\n")
        }
        sb.append("铁律：\n")
        card.taboos.forEach { sb.append("- $it\n") }
        sb.append("- 你在开车场景里陪司机说话：回复必须是口语短句，适合朗读；不要 markdown、不要 emoji、不要网址\n")
        sb.append("- 你看不见车外、看不见屏幕、看不见司机，只能凭对话和下面给你的信息回应\n")
        sb.append("- 本次回复至多 ${style.maxSentences} 句，语气：${style.toneHint}\n")
        if (card.examples.isNotEmpty()) {
            sb.append("说话风格示例：\n")
            card.examples.forEach { (user, reply) ->
                sb.append("司机：$user\n${card.name}：$reply\n")
            }
        }
        if (memories.isNotEmpty()) {
            sb.append("你记得司机这些事（自然地用上，不要背诵清单）：\n")
            memories.forEach { sb.append("- ${it.text}\n") }
        }
        sb.append("当前场景：${scene.displayName}。")
        when (scene) {
            DriveScene.JAM -> sb.append("司机正堵在路上，心情容易烦躁：多安抚，少讲道理。\n")
            DriveScene.NIGHT -> sb.append("深夜行车：语气温柔放轻，多陪伴，留意司机是否犯困。\n")
            DriveScene.FATIGUE -> sb.append("司机可能疲劳：回复更短更温和，适时提醒休息，但不要唠叨。\n")
            DriveScene.CRUISE -> sb.append("路况顺畅：可以正常聊天、找话题。\n")
        }
        if (!navContext.isNullOrBlank()) {
            sb.append("行车信息：$navContext\n")
        }
        return sb.toString()
    }
}
