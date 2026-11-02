package com.qmxz.pilotbotv2.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.qmxz.pilotbotv2.BuildConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Amap geocode + driving-route provider, isolated behind reflection.
 *
 * Same pattern and same caveats as AmapNavigationProvider:
 * - No compile-time dependency on the Amap Search SDK, so the app builds
 *   and runs without the AAR. The provider activates only when the SDK
 *   classes are present AND a key is configured.
 * - A missing SDK, a missing key, or a signature mismatch degrades to
 *   Failed(...) with a readable message. It never throws, never crashes.
 * - Coordinates in and out are GCJ-02, matching the rest of the nav code.
 *
 * UNVERIFIED (same as the location provider): the reflective class and
 * method names below follow the public Amap Search SDK API
 * (RouteSearch / GeocodeSearch), but they have NOT been checked against
 * a real SDK jar here — this VM has no Android SDK and no AAR. Any
 * mismatch surfaces as a Failed message on the first navigation attempt,
 * and the exact name to fix is in that message.
 */
class AmapRouteProvider(context: Context) : RouteProvider {

    override val providerName = "高德"

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun sdkPresent(): Boolean {
        return try {
            Class.forName(ROUTE_SEARCH_CLASS)
            Class.forName(GEOCODE_SEARCH_CLASS)
            Class.forName(LATLON_POINT_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /** Shared gate: key first, then SDK. Returns null when ready. */
    private fun gate(action: String): String? {
        if (BuildConfig.AMAP_KEY.isBlank()) {
            return "未配置高德 Key：$action 前请先在 local.properties 写 AMAP_KEY 后重新编译"
        }
        if (!sdkPresent()) {
            return "高德搜索 SDK 未接入：$action 前请先运行 scripts/fetch-amap-sdk.sh 把 AAR 放到 app/libs"
        }
        return null
    }

    override fun geocode(query: String, city: String?, onResult: (GeocodeResult) -> Unit) {
        gate("导航")?.let { reason ->
            post { onResult(GeocodeResult.Failed(reason)) }
            return
        }
        try {
            val searchClass = Class.forName(GEOCODE_SEARCH_CLASS)
            val listenerInterface = Class.forName(GEOCODE_LISTENER_CLASS)
            val queryClass = Class.forName(GEOCODE_QUERY_CLASS)
            val search = searchClass
                .getConstructor(Context::class.java)
                .newInstance(appContext)
            val queryObj = queryClass
                .getConstructor(String::class.java, String::class.java)
                .newInstance(query, city ?: "")
            val proxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
                InvocationHandler { _, method, args ->
                    if (method.name == "onGeocodeSearched" && args != null && args.size >= 2) {
                        handleGeocodeResult(query, args[0], args[1] as Int, onResult)
                    }
                    null
                }
            )
            searchClass
                .getMethod("setOnGeocodeSearchListener", listenerInterface)
                .invoke(search, proxy)
            searchClass
                .getMethod("getFromLocationNameAsyn", queryClass)
                .invoke(search, queryObj)
        } catch (e: Exception) {
            post { onResult(GeocodeResult.Failed("高德地理编码启动失败：${e.message}")) }
        }
    }

    private fun handleGeocodeResult(
        query: String,
        result: Any,
        rCode: Int,
        onResult: (GeocodeResult) -> Unit
    ) {
        try {
            if (rCode != 1000) {
                post { onResult(GeocodeResult.Failed("高德地理编码失败（错误码 $rCode）")) }
                return
            }
            val list = result.javaClass
                .getMethod("getGeocodeAddressList")
                .invoke(result) as? List<*> ?: emptyList<Any>()
            val first = list.firstOrNull()
            if (first == null) {
                post { onResult(GeocodeResult.Failed("没找到“$query”这个地方，换个说法再试")) }
                return
            }
            val point = first.javaClass.getMethod("getLatLonPoint").invoke(first)
            val lat = (point.javaClass.getMethod("getLatitude").invoke(point) as Number).toDouble()
            val lng = (point.javaClass.getMethod("getLongitude").invoke(point) as Number).toDouble()
            val address = try {
                first.javaClass.getMethod("getFormatAddress").invoke(first) as? String
            } catch (_: Exception) {
                null
            }
            post { onResult(GeocodeResult.Ok(lat, lng, address)) }
        } catch (e: Exception) {
            post { onResult(GeocodeResult.Failed("高德地理编码解析失败：${e.message}")) }
        }
    }

    override fun planRoute(
        fromLatGcj02: Double,
        fromLngGcj02: Double,
        toLatGcj02: Double,
        toLngGcj02: Double,
        destinationName: String,
        onResult: (RouteResult) -> Unit
    ) {
        gate("导航")?.let { reason ->
            post { onResult(RouteResult.Failed(reason)) }
            return
        }
        try {
            val routeClass = Class.forName(ROUTE_SEARCH_CLASS)
            val listenerInterface = Class.forName(ROUTE_LISTENER_CLASS)
            val fromAndToClass = Class.forName("$ROUTE_SEARCH_CLASS\$FromAndTo")
            val queryClass = Class.forName("$ROUTE_SEARCH_CLASS\$DriveRouteQuery")
            val pointClass = Class.forName(LATLON_POINT_CLASS)
            val search = routeClass
                .getConstructor(Context::class.java)
                .newInstance(appContext)
            val fromPoint = pointClass
                .getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                .newInstance(fromLatGcj02, fromLngGcj02)
            val toPoint = pointClass
                .getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                .newInstance(toLatGcj02, toLngGcj02)
            val fromAndTo = fromAndToClass
                .getConstructor(pointClass, pointClass)
                .newInstance(fromPoint, toPoint)
            val mode = routeClass.getField("DRIVING_SINGLE_DEFAULT").get(null) as Int
            val queryObj = queryClass
                .getConstructor(
                    fromAndToClass,
                    Int::class.javaPrimitiveType,
                    List::class.java,
                    List::class.java,
                    String::class.java
                )
                .newInstance(fromAndTo, mode, null, null, "")
            val proxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
                InvocationHandler { _, method, args ->
                    if (method.name == "onDriveRouteSearched" && args != null && args.size >= 2) {
                        handleRouteResult(destinationName, args[0], args[1] as Int, onResult)
                    }
                    null
                }
            )
            routeClass
                .getMethod("setRouteSearchListener", listenerInterface)
                .invoke(search, proxy)
            routeClass
                .getMethod("calculateDriveRouteAsyn", queryClass)
                .invoke(search, queryObj)
        } catch (e: Exception) {
            post { onResult(RouteResult.Failed("高德算路启动失败：${e.message}")) }
        }
    }

    private fun handleRouteResult(
        destinationName: String,
        result: Any,
        errorCode: Int,
        onResult: (RouteResult) -> Unit
    ) {
        try {
            if (errorCode != 1000) {
                post { onResult(RouteResult.Failed("高德算路失败（错误码 $errorCode）")) }
                return
            }
            val paths = result.javaClass
                .getMethod("getPaths")
                .invoke(result) as? List<*> ?: emptyList<Any>()
            val path = paths.firstOrNull()
            if (path == null) {
                post { onResult(RouteResult.Failed("高德没有返回路线")) }
                return
            }
            val distance = (path.javaClass.getMethod("getDistance").invoke(path) as Number).toFloat()
            val duration = (path.javaClass.getMethod("getDuration").invoke(path) as Number).toLong()
            val stepsRaw = path.javaClass
                .getMethod("getSteps")
                .invoke(path) as? List<*> ?: emptyList<Any>()
            val steps = stepsRaw.mapNotNull { step ->
                try {
                    val instruction = step.javaClass
                        .getMethod("getInstruction")
                        .invoke(step) as? String
                        ?: return@mapNotNull null
                    val stepDistance = (step.javaClass
                        .getMethod("getDistance")
                        .invoke(step) as Number).toFloat()
                    val road = try {
                        step.javaClass.getMethod("getRoad").invoke(step) as? String
                    } catch (_: Exception) {
                        null
                    }
                    RouteStep(instruction, stepDistance, road)
                } catch (_: Exception) {
                    null
                }
            }
            if (steps.isEmpty()) {
                post { onResult(RouteResult.Failed("高德路线没有步骤")) }
                return
            }
            post {
                onResult(RouteResult.Ok(PlannedRoute(destinationName, distance, duration, steps)))
            }
        } catch (e: Exception) {
            post { onResult(RouteResult.Failed("高德路线解析失败：${e.message}")) }
        }
    }

    companion object {
        private const val ROUTE_SEARCH_CLASS = "com.amap.api.services.route.RouteSearch"
        private const val ROUTE_LISTENER_CLASS =
            "com.amap.api.services.route.RouteSearch\$OnRouteSearchListener"
        private const val GEOCODE_SEARCH_CLASS = "com.amap.api.services.geocode.GeocodeSearch"
        private const val GEOCODE_LISTENER_CLASS =
            "com.amap.api.services.geocode.GeocodeSearch\$OnGeocodeSearchListener"
        private const val GEOCODE_QUERY_CLASS = "com.amap.api.services.geocode.GeocodeQuery"
        private const val LATLON_POINT_CLASS = "com.amap.api.services.core.LatLonPoint"
    }
}
