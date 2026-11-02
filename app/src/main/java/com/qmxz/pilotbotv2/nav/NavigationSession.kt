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

    /**
     * Fired when the driver has left the route (see [checkOffRoute]).
     * The argument is the destination name; the owner re-plans from the
     * current fix and calls [start] with the new route. The session keeps
     * the old route running until that happens, so a failed re-plan never
     * leaves the driver without any guidance.
     */
    var onOffRoute: ((String) -> Unit)? = null

    private var route: PlannedRoute? = null
    private var stepIndex = 0
    private var distanceIntoStep = 0f
    private var lastFix: NavLocation? = null
    private var offRouteFixCount = 0
    private var lastOffRouteTriggerMs: Long = 0

    fun isActive(): Boolean = route != null

    fun start(route: PlannedRoute, isReplan: Boolean = false) {
        this.route = route
        stepIndex = 0
        distanceIntoStep = 0f
        lastFix = null
        offRouteFixCount = 0
        if (isReplan) {
            // A re-plan is not a fresh trip: suppress off-route detection
            // briefly so a still-settling GPS fix cannot trigger another
            // re-plan immediately.
            lastOffRouteTriggerMs = System.currentTimeMillis()
        }
        val km = route.totalDistanceMeters / 1000f
        val minutes = route.totalDurationSeconds / 60
        val first = route.steps.firstOrNull()?.instruction
        val overview = buildString {
            if (isReplan) {
                append("已重新规划路线，")
            } else {
                append("开始导航，")
            }
            append("前往${route.destinationName}，")
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
        offRouteFixCount = 0
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
        checkOffRoute(fix)
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

    /**
     * Off-route detection, deliberately conservative: GPS in a city can
     * sit 50 m off, so one far fix proves nothing. Only a moving fix
     * (speed >= 5 km/h, same standstill guard as step advancement) more
     * than [OFF_ROUTE_THRESHOLD_M] from the current step's shape, three
     * fixes in a row, counts as leaving the route. Steps without a
     * polyline are skipped, not failed. After a trigger, detection pauses
     * for [OFF_ROUTE_COOLDOWN_MS] so a bad-GPS stretch cannot spam
     * re-plans; the old route keeps guiding meanwhile.
     */
    private fun checkOffRoute(fix: NavLocation) {
        val current = route ?: return
        if (fix.speedKmh < 5f) return
        if (System.currentTimeMillis() - lastOffRouteTriggerMs < OFF_ROUTE_COOLDOWN_MS) {
            return
        }
        val shape = current.steps.getOrNull(stepIndex)?.polyline
        if (shape.isNullOrEmpty()) {
            offRouteFixCount = 0
            return
        }
        val distance = distanceToPolylineMeters(fix.latGcj02, fix.lngGcj02, shape)
        if (distance > OFF_ROUTE_THRESHOLD_M) {
            offRouteFixCount++
            if (offRouteFixCount >= OFF_ROUTE_FIXES) {
                offRouteFixCount = 0
                lastOffRouteTriggerMs = System.currentTimeMillis()
                onOffRoute?.invoke(current.destinationName)
            }
        } else {
            offRouteFixCount = 0
        }
    }

    /**
     * Shortest distance from a point to a polyline, in metres.
     * Equirectangular projection around the point is accurate enough for
     * the ~100 m threshold over short segments.
     */
    private fun distanceToPolylineMeters(
        lat: Double,
        lng: Double,
        shape: List<Pair<Double, Double>>
    ): Float {
        if (shape.size == 1) {
            return haversineMeters(lat, lng, shape[0].first, shape[0].second)
        }
        val cosLat = cos(Math.toRadians(lat))
        val px = lng * cosLat
        val py = lat
        var best = Float.MAX_VALUE
        for (i in 0 until shape.size - 1) {
            val (latA, lngA) = shape[i]
            val (latB, lngB) = shape[i + 1]
            val ax = lngA * cosLat
            val ay = latA
            val bx = lngB * cosLat
            val by = latB
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) {
                0.0
            } else {
                ((px - ax) * dx + (py - ay) * dy) / lengthSquared
            }.coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val distDegrees = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            // One degree of latitude is ~111,320 m; longitude degrees were
            // already scaled by cos(lat) above, so the same factor applies.
            val distMeters = (distDegrees * 111320.0).toFloat()
            if (distMeters < best) {
                best = distMeters
            }
        }
        return best
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

    companion object {
        /** Beyond this distance from the step shape, a fix counts as off-route. */
        private const val OFF_ROUTE_THRESHOLD_M = 100f

        /** Consecutive off-route fixes required before triggering a re-plan. */
        private const val OFF_ROUTE_FIXES = 3

        /** Quiet period after a trigger, so bad GPS cannot spam re-plans. */
        private const val OFF_ROUTE_COOLDOWN_MS = 60_000L
    }
}
