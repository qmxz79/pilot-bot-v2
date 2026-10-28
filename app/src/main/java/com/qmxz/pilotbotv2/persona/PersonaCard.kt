package com.qmxz.pilotbotv2.persona

/**
 * A fixed identity card for the companion.
 *
 * v1 lesson: the persona was just a name + tone word + catchphrase, so the
 * model improvised a different character every few turns ("人设混乱"). v2
 * anchors EVERY reply to this card (see SystemPromptBuilder): identity,
 * personality, catchphrases, taboos, and — most importantly — example
 * dialogues that show the model what "in character" sounds like.
 *
 * The card is data, not code: presets live in [PersonaPresets], and the user
 * picks one in Settings. The name is always 小搭 across presets so the driver
 * never has to relearn who they are talking to.
 */
data class PersonaCard(
    /** Stable id used in settings, e.g. "warm". */
    val id: String,
    /** Always 小搭 — the companion's name never changes between presets. */
    val name: String,
    /** One line: who this companion is to the driver. */
    val identity: String,
    /** Personality in plain words (warm, steady, witty…). */
    val personality: String,
    /** Signature phrases, used sparingly — never every reply. */
    val catchphrases: List<String>,
    /** Hard rules: things this persona never says or does. */
    val taboos: List<String>,
    /** Short example exchanges (user line -> in-character reply). */
    val examples: List<Pair<String, String>>
)
