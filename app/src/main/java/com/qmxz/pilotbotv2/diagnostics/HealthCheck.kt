package com.qmxz.pilotbotv2.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.qmxz.pilotbotv2.llm.LlmClient
import com.qmxz.pilotbotv2.llm.LlmSettings
import com.qmxz.pilotbotv2.voice.AsrProvider
import com.qmxz.pilotbotv2.voice.TtsProvider

/** One self-check item: always a name + pass/fail + a human-readable message. */
data class HealthItem(
    val name: String,
    val ok: Boolean,
    val message: String
)

/**
 * Startup self-check: TTS, ASR (+ mic permission), GPS (+ location permission
 * and service state), and LLM connectivity. Every item reports, nothing is
 * skipped, and the caller (ViewModel) both displays the list and speaks a
 * one-line summary — so a failure is visible AND audible, never silent.
 *
 * The LLM check is async (network ping); [run] calls [onResult] exactly once,
 * after the ping returns or immediately when no key is configured.
 */
class HealthCheck(
    context: Context,
    private val tts: TtsProvider,
    private val asr: AsrProvider,
    private val llm: LlmClient,
    private val settings: LlmSettings
) {
    private val appContext = context.applicationContext

    fun run(onResult: (List<HealthItem>) -> Unit) {
        val items = ArrayList<HealthItem>()

        items.add(
            HealthItem("语音合成", tts.isAvailable(), tts.availabilityMessage())
        )

        val micGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val asrMessage = when {
            !micGranted -> "没有录音权限，请允许录音后重试"
            else -> asr.availabilityMessage()
        }
        items.add(
            HealthItem("语音识别", asr.isAvailable() && micGranted, asrMessage)
        )

        val locationManager = appContext.getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager
        val locationGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val gpsMessage = when {
            !locationGranted -> "没有定位权限，请允许定位后重试"
            !gpsEnabled -> "请开启系统定位服务"
            else -> "定位就绪"
        }
        items.add(
            HealthItem("定位", locationGranted && gpsEnabled, gpsMessage)
        )

        if (settings.apiKey.isBlank()) {
            items.add(HealthItem("大模型", false, "未配置 LLM Key，请在设置页填写"))
            onResult(items)
            return
        }
        llm.ping { ok, message ->
            items.add(HealthItem("大模型", ok, message))
            onResult(items)
        }
    }

    /** One spoken line summarising [items] (all-pass or the failure list). */
    fun spokenSummary(items: List<HealthItem>): String {
        val failures = items.filter { !it.ok }
        return if (failures.isEmpty()) {
            "自检通过，语音、定位、大模型都正常。"
        } else {
            val detail = failures.joinToString("；") { "${it.name}：${it.message}" }
            "自检发现问题：$detail"
        }
    }
}
