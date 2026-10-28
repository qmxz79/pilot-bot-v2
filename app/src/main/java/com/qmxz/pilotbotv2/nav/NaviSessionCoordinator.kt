package com.qmxz.pilotbotv2.nav

import com.qmxz.pilotbotv2.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picks and supervises the active location provider.
 *
 * Policy:
 * - If a key is configured, try Amap first; if Amap fails BEFORE delivering
 *   its first fix, switch to system GPS exactly once. (After the first fix,
 *   later Amap errors are reported but do not flap providers mid-trip.)
 * - If no key is configured, go straight to system GPS — no error spam.
 * - Conversion happens inside each provider, exactly once; this coordinator
 *   never re-converts, so a GCJ-02 fix can never be double-shifted.
 *
 * The coordinator never throws: provider start/stop are wrapped, and the
 * worst case is a status message saying location is unavailable.
 */
class NaviSessionCoordinator(
    private val amapProvider: NavigationProvider,
    private val fallbackProvider: NavigationProvider
) {
    private val _locationFlow = MutableStateFlow<NavLocation?>(null)
    val locationFlow: StateFlow<NavLocation?> = _locationFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow(NavStatus(false, "定位未启动"))
    val statusFlow: StateFlow<NavStatus> = _statusFlow.asStateFlow()

    private var activeProvider: NavigationProvider? = null
    private var switchedToFallback: Boolean = false
    private var amapDeliveredFix: Boolean = false
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        if (BuildConfig.AMAP_KEY.isBlank()) {
            _statusFlow.value = NavStatus(false, "未配置高德 Key，使用系统 GPS")
            startFallback()
        } else {
            startAmap()
        }
    }

    private fun startAmap() {
        activeProvider = amapProvider
        try {
            amapProvider.start(
                onLocation = { fix ->
                    amapDeliveredFix = true
                    _locationFlow.value = fix
                    _statusFlow.value = NavStatus(true, "定位正常（高德）")
                },
                onStatus = { status ->
                    _statusFlow.value = status
                    if (!status.ok && !amapDeliveredFix && !switchedToFallback) {
                        switchedToFallback = true
                        startFallback()
                    }
                }
            )
        } catch (e: Exception) {
            _statusFlow.value = NavStatus(false, "高德启动异常：${e.message}，改用系统 GPS")
            if (!switchedToFallback) {
                switchedToFallback = true
                startFallback()
            }
        }
    }

    private fun startFallback() {
        activeProvider = fallbackProvider
        try {
            fallbackProvider.start(
                onLocation = { fix ->
                    _locationFlow.value = fix
                    _statusFlow.value = NavStatus(true, "定位正常（系统 GPS）")
                },
                onStatus = { status ->
                    _statusFlow.value = status
                }
            )
        } catch (e: Exception) {
            _statusFlow.value = NavStatus(false, "系统定位启动异常：${e.message}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            amapProvider.stop()
        } catch (_: Exception) {
        }
        try {
            fallbackProvider.stop()
        } catch (_: Exception) {
        }
        activeProvider = null
    }

    fun latestLocation(): NavLocation? = _locationFlow.value
}
