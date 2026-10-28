package com.qmxz.pilotbotv2.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 (hardware GPS) <-> GCJ-02 (China national standard, used by Amap).
 *
 * Why this file exists: v1's user symptom "定位不准" is the classic China
 * offset — raw GPS coordinates plotted on a GCJ-02 map sit 300–500 m off.
 * The transform is a public standard formula, reimplemented here from scratch.
 *
 * Critical rule (do not "fix" this): convert EXACTLY ONCE, at the provider
 * boundary. System GPS output is WGS-84 and must be converted; Amap output
 * is ALREADY GCJ-02 and must NOT be converted again (double conversion adds
 * a second, wrong offset). See SystemGpsFallbackProvider and
 * AmapNavigationProvider.
 */
object CoordinateTransform {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** WGS-84 -> GCJ-02. Coordinates outside China are returned unchanged. */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutOfChina(lat, lng)) return Pair(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return Pair(lat + dLat, lng + dLng)
    }

    /** GCJ-02 -> WGS-84 (approximate inverse, same standard formula). */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutOfChina(lat, lng)) return Pair(lat, lng)
        val (gLat, gLng) = wgs84ToGcj02(lat, lng)
        return Pair(lat - (gLat - lat), lng - (gLng - lng))
    }

    fun isOutOfChina(lat: Double, lng: Double): Boolean {
        if (lng < 72.004 || lng > 137.8347) return true
        if (lat < 0.8293 || lat > 55.8271) return true
        return false
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
