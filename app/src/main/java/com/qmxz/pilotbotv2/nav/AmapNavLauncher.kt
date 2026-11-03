package com.qmxz.pilotbotv2.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.qmxz.pilotbotv2.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Destination for an Amap-app navigation jump. Coordinates are GCJ-02. */
data class AmapNavTarget(
    val name: String,
    val latGcj02: Double,
    val lngGcj02: Double
)

/** Geocoding outcome: a target, or a human-readable reason it failed. */
sealed class AmapNavResult {
    data class Ok(val target: AmapNavTarget) : AmapNavResult()
    data class Failed(val message: String) : AmapNavResult()
}

/**
 * Navigation via the Amap app, not in-app routing (decision 2026-11-03).
 *
 * Two pieces, neither needing any Amap SDK:
 * - Geocoding: the Amap Web geocode API over plain HTTP. It still needs
 *   the AMAP_KEY from local.properties (compile-time, never committed),
 *   but no AAR at all.
 * - Jump: the official Amap mobile URI, androidamap://navi, fired as an
 *   ACTION_VIEW intent pinned to com.autonavi.minimap. The Amap app
 *   starts navigation from the user's current position to the target.
 *
 * Results are delivered on the main thread. Nothing here throws: every
 * failure comes back as Failed with a message the driver can understand.
 */
object AmapNavLauncher {

    const val AMAP_PACKAGE = "com.autonavi.minimap"

    private const val GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo"
    private const val PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around"

    /** Around search is capped at 50 km by the Amap API. */
    private const val AROUND_RADIUS_M = 50_000

    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAmapInstalled(context: Context): Boolean {
        return context.packageManager.getLaunchIntentForPackage(AMAP_PACKAGE) != null
    }

    /**
     * Build the official navi intent. The REST geocode returns GCJ-02,
     * which is already encrypted, so dev=0. style is kept for shape even
     * though Amap 8.2.6+ ignores it in favour of the user's own settings.
     */
    fun buildNaviIntent(target: AmapNavTarget): Intent {
        val uri = Uri.parse(
            "androidamap://navi?sourceApplication=" + Uri.encode("副驾搭子") +
                "&poiname=" + Uri.encode(target.name) +
                "&lat=" + target.latGcj02 +
                "&lon=" + target.lngGcj02 +
                "&dev=0&style=2"
        )
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(AMAP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Resolve a place name to GCJ-02 coordinates. */
    fun geocode(query: String, onResult: (AmapNavResult) -> Unit) {
        val key = BuildConfig.AMAP_KEY
        if (key.isBlank()) {
            post {
                onResult(
                    AmapNavResult.Failed(
                        "未配置高德 Key：地名解析需要 Key，请在 local.properties 写 " +
                            "AMAP_KEY 后重新编译（导航本身不再需要 SDK）"
                    )
                )
            }
            return
        }
        val url = GEOCODE_URL +
            "?key=" + Uri.encode(key) +
            "&address=" + Uri.encode(query) +
            "&output=json"
        Thread {
            try {
                val request = Request.Builder().url(url).build()
                val result = httpClient.newCall(request).execute().use { response ->
                    parseGeocode(query, response.body?.string().orEmpty())
                }
                post { onResult(result) }
            } catch (e: Exception) {
                post { onResult(AmapNavResult.Failed("地名解析失败：${e.message}")) }
            }
        }.start()
    }

    /**
     * Find the nearest POI matching [keyword] around a GCJ-02 fix.
     * Around search is inherently local, which is what "本地市政府"
     * means: the nationwide geocode would happily return another
     * city's 市政府 as its first hit. The nearest match wins, picked
     * by the returned distance field — never by trusting the API's order.
     */
    fun searchNearby(
        latGcj02: Double,
        lngGcj02: Double,
        keyword: String,
        onResult: (AmapNavResult) -> Unit
    ) {
        val key = BuildConfig.AMAP_KEY
        if (key.isBlank()) {
            post {
                onResult(
                    AmapNavResult.Failed(
                        "未配置高德 Key：地点搜索需要 Key，请在 local.properties 写 " +
                            "AMAP_KEY 后重新编译（导航本身不再需要 SDK）"
                    )
                )
            }
            return
        }
        // location is "lng,lat" of plain doubles: no encoding needed, and
        // the comma must survive (Amap parses it as the separator).
        val url = PLACE_AROUND_URL +
            "?key=" + Uri.encode(key) +
            "&location=" + lngGcj02 + "," + latGcj02 +
            "&keywords=" + Uri.encode(keyword) +
            "&radius=" + AROUND_RADIUS_M +
            "&offset=10&extensions=base&output=json"
        Thread {
            try {
                val request = Request.Builder().url(url).build()
                val result = httpClient.newCall(request).execute().use { response ->
                    parseAround(keyword, response.body?.string().orEmpty())
                }
                post { onResult(result) }
            } catch (e: Exception) {
                post { onResult(AmapNavResult.Failed("地点搜索失败：${e.message}")) }
            }
        }.start()
    }

    private fun parseAround(keyword: String, body: String): AmapNavResult {
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                return AmapNavResult.Failed(
                    "地点搜索失败：${json.optString("info")}"
                )
            }
            val pois = json.optJSONArray("pois")
            if (pois == null || pois.length() == 0) {
                return AmapNavResult.Failed("附近没找到“$keyword”，换个说法再试")
            }
            var best: AmapNavTarget? = null
            var bestDistance = Double.MAX_VALUE
            for (i in 0 until pois.length()) {
                val poi = pois.getJSONObject(i)
                val parts = poi.optString("location").split(",")
                if (parts.size != 2) continue
                val lng = parts[0].toDoubleOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull() ?: continue
                val distance =
                    poi.optString("distance").toDoubleOrNull() ?: Double.MAX_VALUE
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = AmapNavTarget(
                        poi.optString("name").ifEmpty { keyword }, lat, lng
                    )
                }
            }
            best?.let { AmapNavResult.Ok(it) }
                ?: AmapNavResult.Failed("地点搜索结果异常")
        } catch (e: Exception) {
            AmapNavResult.Failed("地点搜索失败：${e.message}")
        }
    }

    private fun parseGeocode(query: String, body: String): AmapNavResult {
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                return AmapNavResult.Failed(
                    "地名解析失败：${json.optString("info")}"
                )
            }
            val geocodes = json.optJSONArray("geocodes")
            if (geocodes == null || geocodes.length() == 0) {
                return AmapNavResult.Failed("没找到“$query”这个地方，换个说法再试")
            }
            val first = geocodes.getJSONObject(0)
            // REST geocode returns "lng,lat", already GCJ-02.
            val parts = first.optString("location").split(",")
            if (parts.size != 2) {
                return AmapNavResult.Failed("地名解析结果异常")
            }
            val lng = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            if (lng == null || lat == null) {
                return AmapNavResult.Failed("地名解析结果异常")
            }
            val name = first.optString("formatted_address").ifEmpty { query }
            AmapNavResult.Ok(AmapNavTarget(name, lat, lng))
        } catch (e: Exception) {
            AmapNavResult.Failed("地名解析失败：${e.message}")
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
