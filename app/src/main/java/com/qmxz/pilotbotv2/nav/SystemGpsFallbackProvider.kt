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
 * Geocoding runs on a background thread and is throttled to once per 30 s:
 * Geocoder can do network I/O and must never run on the main thread, where
 * LocationListener callbacks arrive.
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

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // LocationListener callbacks arrive on the main thread.
            val (gcjLat, gcjLng) = CoordinateTransform.wgs84ToGcj02(
                location.latitude,
                location.longitude
            )
            val fix = NavLocation(
                latGcj02 = gcjLat,
                lngGcj02 = gcjLng,
                address = lastLocation?.address,
                speedKmh = location.speed * 3.6f,
                source = providerName
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

    private fun maybeGeocode(fix: NavLocation) {
        val now = System.currentTimeMillis()
        if (now - lastGeocodeMs < GEOCODE_THROTTLE_MS) return
        lastGeocodeMs = now
        Thread({
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(appContext, Locale.getDefault())
                    .getFromLocation(fix.latGcj02, fix.lngGcj02, 1)
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
    }
}
