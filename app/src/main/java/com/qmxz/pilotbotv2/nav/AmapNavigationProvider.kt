package com.qmxz.pilotbotv2.nav

import android.content.Context
import com.qmxz.pilotbotv2.BuildConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Amap location provider, isolated behind reflection.
 *
 * v1 lesson: navigation was both fragile and fatal — SDK init problems
 * crashed or silently killed the trip. v2 therefore treats the Amap SDK as
 * an OPTIONAL runtime component:
 *
 * - This file imports NO Amap classes, so the app compiles and runs with
 *   zero map binaries in the repo (see app/build.gradle).
 * - At runtime it checks, in order: key configured? SDK present? init OK?
 *   Every "no" returns a human-readable [NavStatus] and the coordinator
 *   falls back to [SystemGpsFallbackProvider]. Nothing here ever throws
 *   out of start()/stop().
 * - Amap coordinates are ALREADY GCJ-02: they are passed through untouched.
 *   Converting them again would re-introduce the 300–500 m offset.
 *
 * To activate: run scripts/fetch-amap-sdk.sh, place the location AAR in
 * app/libs/, add the documented dependency line, and set AMAP_KEY in
 * local.properties. Without those steps this provider politely declines
 * and the app keeps working on system GPS.
 *
 * UNVERIFIED: the reflective method names below follow the public Amap
 * Location SDK API (AMapLocationClient / AMapLocationClientOption /
 * AMapLocationListener), but they have NOT been checked against an actual
 * SDK jar in this environment — if the SDK's signatures differ, init fails
 * gracefully and the fallback takes over. Verify on first real-device run
 * with the SDK present.
 */
class AmapNavigationProvider(context: Context) : NavigationProvider {

    override val providerName: String = "高德"

    private val appContext = context.applicationContext
    private var client: Any? = null
    private var onLocation: ((NavLocation) -> Unit)? = null
    private var onStatus: ((NavStatus) -> Unit)? = null
    private var lastLocation: NavLocation? = null
    private var started: Boolean = false

    /** True when the Amap location classes are loadable at runtime. */
    fun isSdkPresent(): Boolean {
        return try {
            Class.forName(CLIENT_CLASS) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun start(
        onLocation: (NavLocation) -> Unit,
        onStatus: (NavStatus) -> Unit
    ) {
        this.onLocation = onLocation
        this.onStatus = onStatus
        if (started) return
        if (BuildConfig.AMAP_KEY.isBlank()) {
            onStatus(NavStatus(false, "未配置高德 Key：在 local.properties 写 AMAP_KEY 后重新编译"))
            return
        }
        if (!isSdkPresent()) {
            onStatus(
                NavStatus(
                    false,
                    "高德定位 SDK 未接入：先运行 scripts/fetch-amap-sdk.sh 把 AAR 放到 app/libs"
                )
            )
            return
        }
        try {
            val clientClass = Class.forName(CLIENT_CLASS)
            val listenerInterface = Class.forName(LISTENER_CLASS)
            val optionClass = Class.forName(OPTION_CLASS)

            val newClient = clientClass
                .getConstructor(Context::class.java)
                .newInstance(appContext)

            val option = optionClass.getDeclaredConstructor().newInstance()
            // NOTE: Amap's enum constant is famously misspelled "Hight_Accuracy".
            val modeClass = Class.forName("$OPTION_CLASS\$AMapLocationMode")
            val highAccuracy = modeClass.getField("Hight_Accuracy").get(null)
            optionClass.getMethod("setLocationMode", modeClass).invoke(option, highAccuracy)
            optionClass.getMethod("setInterval", Long::class.javaPrimitiveType)
                .invoke(option, 2000L)
            optionClass.getMethod("setNeedAddress", Boolean::class.javaPrimitiveType)
                .invoke(option, true)

            val proxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
                InvocationHandler { _, method, args ->
                    if (method.name == "onLocationChanged" && args != null && args.isNotEmpty()) {
                        handleAmapLocation(args[0])
                    }
                    null
                }
            )
            clientClass.getMethod("setLocationListener", listenerInterface)
                .invoke(newClient, proxy)
            clientClass.getMethod("setLocationOption", optionClass)
                .invoke(newClient, option)
            clientClass.getMethod("startLocation").invoke(newClient)

            client = newClient
            started = true
            onStatus(NavStatus(true, "定位正常（高德）"))
        } catch (e: Exception) {
            onStatus(NavStatus(false, "高德 SDK 初始化失败：${e.message}，将改用系统 GPS"))
        }
    }

    private fun handleAmapLocation(amapLocation: Any) {
        try {
            val clazz = amapLocation.javaClass
            val errorCode = clazz.getMethod("getErrorCode").invoke(amapLocation) as Int
            if (errorCode != 0) {
                val errorInfo = clazz.getMethod("getErrorInfo").invoke(amapLocation) as? String
                onStatus?.invoke(
                    NavStatus(false, "高德定位失败：${errorInfo ?: "未知错误"}（错误码 $errorCode）")
                )
                return
            }
            val lat = clazz.getMethod("getLatitude").invoke(amapLocation) as Double
            val lng = clazz.getMethod("getLongitude").invoke(amapLocation) as Double
            val address = clazz.getMethod("getAddress").invoke(amapLocation) as? String
            val speedMs = try {
                clazz.getMethod("getSpeed").invoke(amapLocation) as Float
            } catch (_: Exception) {
                0f
            }
            // Amap output is already GCJ-02 — DO NOT convert again.
            val fix = NavLocation(
                latGcj02 = lat,
                lngGcj02 = lng,
                address = address,
                speedKmh = speedMs * 3.6f,
                source = providerName
            )
            lastLocation = fix
            onLocation?.invoke(fix)
        } catch (e: Exception) {
            onStatus?.invoke(NavStatus(false, "高德定位回调解析失败：${e.message}"))
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        val current = client
        client = null
        if (current != null) {
            try {
                current.javaClass.getMethod("stopLocation").invoke(current)
            } catch (_: Exception) {
            }
            try {
                current.javaClass.getMethod("onDestroy").invoke(current)
            } catch (_: Exception) {
            }
        }
        onLocation = null
        onStatus = null
    }

    override fun latestLocation(): NavLocation? = lastLocation

    private companion object {
        const val CLIENT_CLASS = "com.amap.api.location.AMapLocationClient"
        const val LISTENER_CLASS = "com.amap.api.location.AMapLocationListener"
        const val OPTION_CLASS = "com.amap.api.location.AMapLocationClientOption"
    }
}
