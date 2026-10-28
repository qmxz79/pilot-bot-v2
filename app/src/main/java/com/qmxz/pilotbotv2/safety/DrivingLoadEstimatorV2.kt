package com.qmxz.pilotbotv2.safety

/**
 * Driving scenes the user picks (or the app later detects) from the scene chips.
 * A scene shapes tone and proactivity — it never silences the companion.
 */
enum class DriveScene(val displayName: String) {
    CRUISE("巡航"),
    JAM("堵车"),
    NIGHT("深夜"),
    FATIGUE("疲劳")
}

/**
 * How the companion should shape ONE reply: length cap + tone.
 * [speakSoftly] is a hint for future cloud TTS (gentler voice); the system
 * TTS path ignores it, which is fine — shaping never depends on the engine.
 */
data class SpeechStyle(
    val maxSentences: Int,
    val toneHint: String,
    val speakSoftly: Boolean = false
)

/**
 * v2 speed/scene estimator — and an explicit correction of v1's biggest
 * safety-design mistake.
 *
 * v1 had a four-tier load gate where L0 (speed < 15 km/h, i.e. traffic jam)
 * meant TOTAL SILENCE: navigation rewrites and proactive speech were dropped.
 * That silenced the companion exactly when the driver most wanted company —
 * stuck in a jam, or just starting out. Users experienced it as "没有声音",
 * a bug, not a feature.
 *
 * v2 rule: speed and scene only ever shape replies (shorter, gentler) —
 * they NEVER mute them. And two things bypass shaping entirely:
 * 1. Navigation announcements always pass through, at any speed.
 * 2. User-initiated chat is never limited — the driver asked, so answer.
 */
object DrivingLoadEstimatorV2 {

    /**
     * Base style from speed alone.
     * - Highway (> 80 km/h): short, affirmative — attention belongs on the road.
     * - Congested (< 15 km/h): short, gentle — the driver is frustrated, not deaf.
     * - Urban (everything else): normal conversation length.
     */
    fun styleForSpeed(speedKmh: Float): SpeechStyle {
        return when {
            speedKmh > 80f -> SpeechStyle(
                maxSentences = 2,
                toneHint = "简短肯定，像报路况一样利落"
            )
            speedKmh < 15f -> SpeechStyle(
                maxSentences = 2,
                toneHint = "轻声舒缓，多安抚",
                speakSoftly = true
            )
            else -> SpeechStyle(
                maxSentences = 4,
                toneHint = "自然放松"
            )
        }
    }

    /** Scene modifiers applied on top of the speed style. Never silence. */
    fun styleFor(speedKmh: Float, scene: DriveScene): SpeechStyle {
        val base = styleForSpeed(speedKmh)
        return when (scene) {
            DriveScene.FATIGUE -> base.copy(
                maxSentences = minOf(base.maxSentences, 2),
                toneHint = "更短更温和，适时提醒休息",
                speakSoftly = true
            )
            DriveScene.NIGHT -> base.copy(
                toneHint = base.toneHint + "，语气温柔放轻",
                speakSoftly = true
            )
            DriveScene.JAM -> base.copy(
                maxSentences = minOf(base.maxSentences, 2),
                toneHint = "多安抚，少讲道理"
            )
            DriveScene.CRUISE -> base
        }
    }
}
