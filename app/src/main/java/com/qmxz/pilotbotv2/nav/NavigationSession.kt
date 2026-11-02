package com.qmxz.pilotbotv2.nav

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks one active route and turns location fixes into spoken
 * maneuver announcements.
 *
 * How steps advance — odometer, not map matching:
 * each location fix adds the distance travelled since the previous fix
 * (haversine between the two GCJ-02 fixes; the GCJ-02 offset is nearly
 * constant over short hops, so the difference is accurate) to the current
 * step. When the travelled distance reaches the step's length, the
 * session moves to the next step and announces its instruction.
 *
 * Two guards keep GPS noise from advancing steps early:
 * - fixes slower than 5 km/h are ignored (standstill GPS wander would
 *   otherwise accumulate phantom distance);
 * - a jump over 500 m between two fixes is ignored (a bad fix, not a
 *   teleport).
 *
 * Known limitation: without map matching, step transitions drift on
 * complex interchanges and parallel roads — the announcement may come
 * a little early or late there. The route line always shows the next
 * instruction, so the driver can see where it thinks it is.
 *
 * Announcements interrupt: the ViewModel speaks them through
 * pipeline.speakText, which replaces any in-progress reply. Navigation
 * outranks chat — a turn instruction must not wait for a sentence to
 * finish.
 */
class NavigationSession {

    /** Spoken announcement (also shown as a SYSTEM transcript row). */
    var onAnnouncement: ((String) -> Unit)? = null

    /** Route summary line for the UI. */
    var onRouteUpdate: ((String) -> Unit)? = null

    private var route: PlannedRoute? = null
    private var stepIndex = 0
    private var distanceIntoStep = 0f
    private var lastFix: NavLocation? = null

    fun isActive(): Boolean = route != null

    fun start(route: PlannedRoute) {
        this.route = route
        stepIndex = 0
        distanceIntoStep = 0f
        lastFix = null
        val km = route.totalDistanceMeters / 1000f
        val minutes = route.totalDurationSeconds / 60
        val first = route.steps.firstOrNull()?.instruction
        val overview = buildString {
            append("开始导航，前往${route.destinationName}，")
            append("全程约${String.format(Locale.US, "%.1f", km)}公里，")
            append("大约${minutes}分钟。")
            if (first != null) {
                append("首先，$first")
            }
        }
        announce(overview)
        pushUpdate()
    }

    fun stop() {
        route = null
        stepIndex = 0
        distanceIntoStep = 0f
        lastFix = null
        pushUpdate()
    }

    fun onLocation(fix: NavLocation) {
        val current = route ?: return
        val prev = lastFix
        lastFix = fix
        if (prev != null) {
            val travelled = haversineMeters(
                prev.latGcj02, prev.lngGcj02, fix.latGcj02, fix.lngGcj02
            )
            if (travelled < 500f && fix.speedKmh >= 5f) {
                distanceIntoStep += travelled
            }
        }
        val steps = current.steps
        while (stepIndex < steps.size &&
            distanceIntoStep >= steps[stepIndex].distanceMeters.coerceAtLeast(0f)
        ) {
            distanceIntoStep -= steps[stepIndex].distanceMeters.coerceAtLeast(0f)
            stepIndex++
            if (stepIndex >= steps.size) {
                val destination = current.destinationName
                stop()
                announce("已经到达${destination}附近，导航结束。")
                return
            }
            announce(steps[stepIndex].instruction)
        }
        pushUpdate()
    }

    fun summary(): String {
        val current = route ?: return "导航未启动"
        var remaining = 0f
        for (i in stepIndex until current.steps.size) {
            remaining += current.steps[i].distanceMeters
        }
        remaining = (remaining - distanceIntoStep).coerceAtLeast(0f)
        val next = current.steps.getOrNull(stepIndex)?.instruction ?: "到达目的地"
        return "导航：前往${current.destinationName} · " +
            "还剩约${String.format(Locale.US, "%.1f", remaining / 1000f)}公里 · " +
            "下一步：$next"
    }

    private fun announce(text: String) {
        onAnnouncement?.invoke(text)
    }

    private fun pushUpdate() {
        onRouteUpdate?.invoke(summary())
    }

    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Float {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }
}
