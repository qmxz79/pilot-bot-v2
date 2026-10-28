package com.qmxz.pilotbotv2.llm

import android.content.Context

/**
 * LLM connection settings, stored in the app's private SharedPreferences.
 *
 * Keys never leave the device and never appear in code, logs, or git:
 * - base_url / model have public defaults (DeepSeek-compatible endpoint);
 * - api_key defaults to empty and is filled ONLY through the in-app Settings
 *   dialog. An empty key makes LlmClient fail loudly with "未配置 LLM Key"
 *   instead of sending a request that cannot work.
 *
 * Uses the plain SharedPreferences editor API on purpose: core-ktx's edit{}
 * extension is not in this project's dependency list.
 */
class LlmSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.trim()).apply()
        }

    var personaId: String
        get() = prefs.getString(KEY_PERSONA_ID, DEFAULT_PERSONA_ID) ?: DEFAULT_PERSONA_ID
        set(value) {
            prefs.edit().putString(KEY_PERSONA_ID, value.trim()).apply()
        }

    private companion object {
        const val PREFS_NAME = "pilotbotv2_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PERSONA_ID = "persona_id"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_PERSONA_ID = "warm"
    }
}
