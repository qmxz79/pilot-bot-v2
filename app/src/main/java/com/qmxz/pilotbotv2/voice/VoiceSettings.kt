package com.qmxz.pilotbotv2.voice

import android.content.Context

/**
 * Voice-engine settings, stored in the app's private SharedPreferences
 * (same file as [com.qmxz.pilotbotv2.llm.LlmSettings], different keys).
 *
 * Same rule as the LLM key: iFlytek credentials are filled ONLY through the
 * in-app Settings dialog, never appear in code, logs, or git, and never
 * leave the device except inside the signed iFlytek WebSocket handshake.
 *
 * Engine selection is honoured on the NEXT round, not mid-sentence: the
 * pipeline resolves its provider per speak/listen call (see
 * [SwitchableTtsProvider]/[SwitchableAsrProvider]), so switching never
 * restarts the app and never cuts off a sentence already playing.
 */
class VoiceSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** "system" or "xfyun" (the user's choice, before fallback). */
    var engine: String
        get() = prefs.getString(KEY_ENGINE, ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value.trim()).apply()
        }

    var xfyunAppId: String
        get() = prefs.getString(KEY_XFYUN_APP_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_XFYUN_APP_ID, value.trim()).apply()
        }

    var xfyunApiKey: String
        get() = prefs.getString(KEY_XFYUN_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_XFYUN_API_KEY, value.trim()).apply()
        }

    var xfyunApiSecret: String
        get() = prefs.getString(KEY_XFYUN_API_SECRET, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_XFYUN_API_SECRET, value.trim()).apply()
        }

    /** iFlytek voice id; xiaoyan is the standard Mandarin female voice. */
    var xfyunVoice: String
        get() = prefs.getString(KEY_XFYUN_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) {
            val clean = value.trim()
            prefs.edit().putString(
                KEY_XFYUN_VOICE,
                clean.ifBlank { DEFAULT_VOICE }
            ).apply()
        }

    /** True only when all three iFlytek credentials are filled. */
    val isXfyunConfigured: Boolean
        get() = xfyunAppId.isNotBlank() &&
            xfyunApiKey.isNotBlank() &&
            xfyunApiSecret.isNotBlank()

    /**
     * The engine actually used: "xfyun" only when the user chose it AND
     * credentials are present. Anything else falls back to system voice —
     * a phone that cannot do cloud voice must still talk, not go silent.
     */
    val effectiveEngine: String
        get() = if (engine == ENGINE_XFYUN && isXfyunConfigured) {
            ENGINE_XFYUN
        } else {
            ENGINE_SYSTEM
        }

    private companion object {
        const val PREFS_NAME = "pilotbotv2_settings"
        const val KEY_ENGINE = "voice_engine"
        const val KEY_XFYUN_APP_ID = "xfyun_app_id"
        const val KEY_XFYUN_API_KEY = "xfyun_api_key"
        const val KEY_XFYUN_API_SECRET = "xfyun_api_secret"
        const val KEY_XFYUN_VOICE = "xfyun_voice"
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_XFYUN = "xfyun"
        const val DEFAULT_VOICE = "xiaoyan"
    }
}
