package com.typeless.ime

import android.content.Context
import android.content.SharedPreferences

/**
 * SettingsManager
 * 
 * 負責管理輸入法的偏好設定，包括 Gemini API Key 與模型選擇。
 * 存儲於 Android 原生 SharedPreferences。
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("typeless_ime_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }

    var apiKey: String?
        get() = prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()?.ifEmpty { null }
        set(value) {
            prefs.edit().putString(KEY_GEMINI_API_KEY, value?.trim()).apply()
        }

    var groqApiKey: String?
        get() = prefs.getString(KEY_GROQ_API_KEY, null)?.trim()?.ifEmpty { null }
        set(value) {
            prefs.edit().putString(KEY_GROQ_API_KEY, value?.trim()).apply()
        }

    var model: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()
        }

    val hasApiKey: Boolean
        get() = !apiKey.isNullOrBlank()

    val hasGroqApiKey: Boolean
        get() = !groqApiKey.isNullOrBlank()
}
