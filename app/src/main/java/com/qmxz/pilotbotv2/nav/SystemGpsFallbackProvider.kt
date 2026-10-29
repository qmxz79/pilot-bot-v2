package com.qmxz.pilotbotv2.nav

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Fallback location source: raw Android LocationManager + Geocoder.
 *
 * This is what keeps v2 working when the Amap SDK is absent, its key is not
 * configured, or its init fails. Raw GPS output is WGS-84, so every fix is
 * converted to GCJ-02 here — exactly once — before leaving this provider.
 *
 * Fix filtering (real-device finding 2026-10-28: position off by hundreds
 * of metres, speed alternating 0 and jumping):
 * - GPS and network fixes share one listener, but they are NOT equal.
 *   Network fixes are coarse cell/WiFi estimates and carry no speed, so a
 *   network fix is accepted only when GPS has gone quiet AND its own
 *   accuracy is usable; otherwise it would drag a good GPS track sideways
 *   and zero the speed display mid-drive.
 * - Speed is carried forward when a fix has none, and lightly smoothed
 *   (exponential average) when it does, so the display neither drops to
 *   0 nor jumps several km/h fix to fix.
 *
 * Geocoding runs on a background thread and is throttled to once per 30 s:
 * Geocoder can do network I/O and must never run on the main thread, where
 * LocationListener callbacks arrive. Geocoder expects WGS-84, so the fix
 * is converted back before lookup (feeding it GCJ-02 shifts the returned
 * street by the same China offset the forward transform removes).
 */
class SystemGpsFallbackProvider(context: Context) : NavigationProvider {

    override val providerName: String = "系统 GPS"

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onLocation: ((NavLocation) -> Unit)? = null
    private var onStatus: ((NavStatus) -> Unit)? = null
    private var lastLocation: NavLocation? = null
    private var lastGeocodeMs: Long = 0
    private var started: Boolean = false

    /** Last time a GPS (not network) fix arrived; 0 means none yet. */
    private var lastGpsMs: Long = 0

    /** Last accepted speed, carried forward across speed-less fixes. */
    private var lastSpeedKmh: Float? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // LocationListener callbacks arrive on the main thread.
            val isGps = location.provider == LocationManager.GPS_PROVIDER
            val now = System.currentTimeMillis()
            if (!isGps) {
                val gpsStale = now - lastGpsMs > GPS_STALE_MS
                val accuracyOk = !location.hasAccuracy() ||
                    location.accuracy <= MAX_NETWORK_ACCURACY_M
                if (!gpsStale || !accuracyOk) {
                    // A fresh GPS track must never be overwritten by a
                    // coarse network estimate, and a wildly inaccurate
                    // network fix is worse than holding the last fix.
                    return
                }
            } else {
                lastGpsMs = now
            }
            val (gcjLat, gcjLng) = CoordinateTransform.wgs84ToGcj02(
                location.latitude,
                location.longitude
            )
            val fix = NavLocation(
                latGcj02 = gcjLat,
                lngGcj02 = gcjLng,
                address = lastLocation?.address,
                speedKmh = smoothedSpeed(location),
                source = if (isGps) providerName else "网络定位"
            )
            lastLocation = fix
            onLocation?.invoke(fix)
            maybeGeocode(fix)
        }

        // The remaining callbacks are required by the interface on older APIs;
        // they carry no information this provider needs.
        @Deprecated("deprecated in the platform API")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            onStatus?.invoke(NavStatus(false, "系统定位服务已关闭，请开启定位"))
        }
    }

    override fun start(
        onLocation: (NavLocation) -> Unit,
        onStatus: (NavStatus) -> Unit
    ) {
        this.onLocation = onLocation
        this.onStatus = onStatus
        if (started) return
        try {
            val gpsEnabled = try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
                false
            }
            val networkEnabled = try {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {
                false
            }
            if (!gpsEnabled && !networkEnabled) {
                onStatus(NavStatus(false, "请开启系统定位服务"))
                return
            }
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 2000L, 5f, listener
                )
            }
            if (networkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 10f, listener
                )
            }
            started = true
            onStatus(NavStatus(true, "定位正常（系统 GPS）"))
        } catch (e: SecurityException) {
            onStatus(NavStatus(false, "没有定位权限，请在系统设置中允许定位"))
        } catch (e: Exception) {
            onStatus(NavStatus(false, "系统定位启动失败：${e.message}"))
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }
        onLocation = null
        onStatus = null
    }

    override fun latestLocation(): NavLocation? = lastLocation

    /**
     * Speed with two guards. A fix without speed (typical network fix)
     * carries the last known speed instead of zeroing the display
     * mid-drive; a fix with speed is lightly averaged so raw GPS jitter
     * does not make the display jump several km/h fix to fix.
     */
    private fun smoothedSpeed(location: Location): Float {
        val previous = lastSpeedKmh
        val next = if (location.hasSpeed()) {
            val raw = (location.speed * 3.6f).coerceAtLeast(0f)
            if (previous == null) {
                raw
            } else {
                SPEED_ALPHA * raw + (1 - SPEED_ALPHA) * previous
            }
        } else {
            previous ?: 0f
        }
        lastSpeedKmh = next
        return next
    }

    private fun maybeGeocode(fix: NavLocation) {
        val now = System.currentTimeMillis()
        if (now - lastGeocodeMs < GEOCODE_THROTTLE_MS) return
        lastGeocodeMs = now
        Thread({
            try {
                // Geocoder expects WGS-84; the fix stores GCJ-02, so
                // convert back first — feeding GCJ-02 straight in shifts
                // the returned street by the China offset.
                val (wgsLat, wgsLng) = CoordinateTransform.gcj02ToWgs84(
                    fix.latGcj02,
                    fix.lngGcj02
                )
                @Suppress("DEPRECATION")
                val addresses = Geocoder(appContext, Locale.getDefault())
                    .getFromLocation(wgsLat, wgsLng, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0)
                if (!address.isNullOrBlank()) {
                    mainHandler.post {
                        val updated = lastLocation?.copy(address = address)
                        if (updated != null) {
                            lastLocation = updated
                            onLocation?.invoke(updated)
                        }
                    }
                }
            } catch (_: Exception) {
                // Geocoding is best-effort; a missing address never blocks location.
            }
        }, "geocoder").start()
    }

    private companion object {
        const val GEOCODE_THROTTLE_MS = 30_000L

        /** Network fixes fill in only after GPS has been quiet this long. */
        const val GPS_STALE_MS = 30_000L

        /** A network fix coarser than this is worse than holding position. */
        const val MAX_NETWORK_ACCURACY_M = 500f

        /** Weight of the newest speed sample in the exponential average. */
        const val SPEED_ALPHA = 0.5f
    }
}
