package com.qmxz.pilotbotv2.safety

/**
 * Automatic scene detection: fatigue / night / jam.
 *
 * Pure logic, no Android dependencies, so the rules can be reasoned about
 * (and later unit-tested) without a device. The ViewModel feeds it the
 * clock, the smoothed speed (null when location is stale), the current
 * scene, and the manual-override deadline; it returns the scene to switch
 * to, or null for no change. Speaking the switch line stays with
 * CopilotViewModel.onSceneSelected, so auto and manual switches sound
 * identical — the driver hears the companion, not a system alert.
 *
 * Rules (thresholds are starting points for real-device tuning, not
 * validated constants):
 * - NIGHT: clock in 22:00–06:00 and the current scene is CRUISE. Night
 *   never steals JAM or FATIGUE: a jam that clears at night recovers to
 *   NIGHT, not CRUISE.
 * - JAM: smoothed speed below 12 km/h sustained for 90 s. A long red
 *   light can false-trigger this; that is the accepted v2 trade-off
 *   (a wrong "前面堵着呢" is mildly off, a missed jam is a missed
 *   companion moment). Jam clears after 60 s above 30 km/h.
 * - FATIGUE: 120 min of continuous driving. The clock starts at the
 *   first evaluation (app start), so opening the app mid-trip warns
 *   late, never early. A 15 min near-standstill counts as a rest and
 *   resets the clock; fatigue fires once per session (until a rest).
 *
 * Priority: rest-reset > manual override (suppresses all) > FATIGUE >
 * JAM > jam-cleared recovery > NIGHT. A scene never re-fires within
 * 30 min of itself, so a persistent condition cannot nag every tick —
 * but a manual override only buys 30 min before a still-true condition
 * re-asserts once, which for fatigue is the safer behaviour.
 *
 * What this deliberately does NOT do: it never silences anything (a scene
 * only shapes tone and speaks one line), and it never fires while the
 * pipeline is busy — the ViewModel only calls it when IDLE, so an
 * auto-switch never cuts off a reply mid-sentence.
 */
class SceneAutoDetector {

    /** First evaluation time: the session clock for fatigue. */
    private var sessionStartMs: Long? = null

    /** First time speed dropped below [JAM_SPEED_KMH] (null when not slow). */
    private var jamSinceMs: Long? = null

    /** First time speed rose above [JAM_CLEAR_SPEED_KMH] while in JAM. */
    private var jamClearSinceMs: Long? = null

    /** First time speed dropped below [REST_SPEED_KMH] (null when moving). */
    private var restSinceMs: Long? = null

    /** True once fatigue has fired this session (a rest clears it). */
    private var fatigueFired: Boolean = false

    /** Last scene this detector triggered, for the retrigger cooldown. */
    private var lastTriggeredScene: DriveScene? = null
    private var lastTriggeredMs: Long = 0

    /**
     * Evaluate the current situation. Call frequently (location updates
     * and a periodic tick); the sustained-duration rules make the exact
     * cadence unimportant.
     *
     * @param speedKmh smoothed speed, or null when location is stale —
     *   jam/rest tracking freezes instead of reasoning from old data.
     */
    fun evaluate(
        nowMs: Long,
        hour: Int,
        speedKmh: Float?,
        currentScene: DriveScene,
        manualOverrideUntilMs: Long
    ): DriveScene? {
        if (sessionStartMs == null) {
            sessionStartMs = nowMs
        }

        // Trackers update even under manual override, so a sustained
        // condition is already established when the override expires.
        if (speedKmh != null) {
            if (speedKmh < JAM_SPEED_KMH) {
                if (jamSinceMs == null) jamSinceMs = nowMs
                jamClearSinceMs = null
            } else {
                jamSinceMs = null
                if (currentScene == DriveScene.JAM) {
                    if (speedKmh > JAM_CLEAR_SPEED_KMH) {
                        if (jamClearSinceMs == null) jamClearSinceMs = nowMs
                    } else {
                        jamClearSinceMs = null
                    }
                } else {
                    jamClearSinceMs = null
                }
            }
            if (speedKmh < REST_SPEED_KMH) {
                if (restSinceMs == null) restSinceMs = nowMs
            } else {
                restSinceMs = null
            }
        }

        // A real rest resets the fatigue clock. If the driver was in
        // FATIGUE, the rest also ends that scene (back to night/cruise).
        val restSince = restSinceMs
        if (restSince != null && nowMs - restSince >= REST_MS) {
            sessionStartMs = nowMs
            restSinceMs = null
            fatigueFired = false
            if (currentScene == DriveScene.FATIGUE) {
                return fire(nowMs, recoveryTarget(hour))
            }
        }

        // The driver's own choice wins for a while.
        if (nowMs < manualOverrideUntilMs) {
            return null
        }

        val start = sessionStartMs ?: nowMs
        if (!fatigueFired && nowMs - start >= FATIGUE_AFTER_MS &&
            currentScene != DriveScene.FATIGUE
        ) {
            fatigueFired = true
            return fire(nowMs, DriveScene.FATIGUE)
        }

        val jamSince = jamSinceMs
        if (jamSince != null && nowMs - jamSince >= JAM_SUSTAIN_MS &&
            currentScene != DriveScene.JAM &&
            currentScene != DriveScene.FATIGUE
        ) {
            return fire(nowMs, DriveScene.JAM)
        }

        val jamClearSince = jamClearSinceMs
        if (currentScene == DriveScene.JAM && jamClearSince != null &&
            nowMs - jamClearSince >= JAM_CLEAR_SUSTAIN_MS
        ) {
            return fire(nowMs, recoveryTarget(hour))
        }

        if (isNight(hour) && currentScene == DriveScene.CRUISE) {
            return fire(nowMs, DriveScene.NIGHT)
        }

        return null
    }

    private fun recoveryTarget(hour: Int): DriveScene {
        return if (isNight(hour)) DriveScene.NIGHT else DriveScene.CRUISE
    }

    private fun isNight(hour: Int): Boolean {
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    /** Cooldown guard: the same scene cannot re-fire within the window. */
    private fun fire(nowMs: Long, target: DriveScene): DriveScene? {
        if (target == lastTriggeredScene &&
            nowMs - lastTriggeredMs < RETRIGGER_COOLDOWN_MS
        ) {
            return null
        }
        lastTriggeredScene = target
        lastTriggeredMs = nowMs
        return target
    }

    private companion object {
        const val NIGHT_START_HOUR = 22
        const val NIGHT_END_HOUR = 6
        const val JAM_SPEED_KMH = 12f
        const val JAM_SUSTAIN_MS = 90_000L
        const val JAM_CLEAR_SPEED_KMH = 30f
        const val JAM_CLEAR_SUSTAIN_MS = 60_000L
        const val FATIGUE_AFTER_MS = 120 * 60_000L
        const val REST_SPEED_KMH = 1f
        const val REST_MS = 15 * 60_000L
        const val RETRIGGER_COOLDOWN_MS = 30 * 60_000L
    }
}
