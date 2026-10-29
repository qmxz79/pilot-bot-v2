package com.qmxz.pilotbotv2.nav

/**
 * One location fix. Coordinates are ALWAYS GCJ-02 by the time they leave a
 * provider (see CoordinateTransform for why conversion happens exactly once,
 * at the provider boundary). Speed is km/h, derived from the provider's own
 * m/s value.
 */
data class NavLocation(
    val latGcj02: Double,
    val lngGcj02: Double,
    val address: String?,
    val speedKmh: Float,
    /**
     * "高德", "系统 GPS" (GPS fix) or "网络定位" (network fix, coarse) —
     * shown in the UI so the driver knows the source (and its accuracy).
     */
    val source: String
)

/**
 * Provider health in plain words. [ok] = this provider is currently usable;
 * [message] is always human-readable and safe to show in the UI.
 */
data class NavStatus(
    val ok: Boolean,
    val message: String
)

/**
 * A location source. Implementations must never throw from start/stop and
 * must report problems through [onStatus] instead of crashing — v1's nav
 * failures were silent or fatal, and v2 treats both as bugs.
 */
interface NavigationProvider {
    val providerName: String
    fun start(onLocation: (NavLocation) -> Unit, onStatus: (NavStatus) -> Unit)
    fun stop()
    fun latestLocation(): NavLocation?
}
