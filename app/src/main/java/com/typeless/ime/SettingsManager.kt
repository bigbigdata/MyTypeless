package com.typeless.ime

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SettingsManager
 * 
 * Manages user preferences for the input method, including Groq/Gemini API keys, model selection,
 * and transparent daily Gemini API call & token usage statistics.
 * Persisted in Android SharedPreferences.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("typeless_ime_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_FORCED_OFFLINE_MODE = "forced_offline_mode"
        private const val KEY_USAGE_DATE = "gemini_usage_date"
        private const val KEY_DAILY_GEMINI_CALLS = "gemini_daily_calls"
        private const val KEY_DAILY_GEMINI_TOKENS = "gemini_daily_tokens"
        private const val KEY_LAST_CALL_TOKENS = "gemini_last_call_tokens"
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"

        private fun todayString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
    }

    var isForcedOfflineMode: Boolean
        get() = prefs.getBoolean(KEY_FORCED_OFFLINE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FORCED_OFFLINE_MODE, value).apply()
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
        get() {
            val saved = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL)?.trim()
            return if (saved.isNullOrEmpty() || saved == "gemini-2.5-flash") {
                DEFAULT_MODEL
            } else {
                saved
            }
        }
        set(value) {
            prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()
        }

    val hasApiKey: Boolean
        get() = !apiKey.isNullOrBlank()

    val hasGroqApiKey: Boolean
        get() = !groqApiKey.isNullOrBlank()

    @Synchronized
    private fun ensureTodayLedger() {
        val today = todayString()
        val storedDate = prefs.getString(KEY_USAGE_DATE, null)
        if (storedDate != today) {
            prefs.edit()
                .putString(KEY_USAGE_DATE, today)
                .putInt(KEY_DAILY_GEMINI_CALLS, 0)
                .putInt(KEY_DAILY_GEMINI_TOKENS, 0)
                .putInt(KEY_LAST_CALL_TOKENS, 0)
                .apply()
        }
    }

    @Synchronized
    fun recordGeminiUsage(tokens: Int) {
        ensureTodayLedger()
        val safeTokens = tokens.coerceAtLeast(0)
        val calls = prefs.getInt(KEY_DAILY_GEMINI_CALLS, 0) + 1
        val totalTokens = prefs.getInt(KEY_DAILY_GEMINI_TOKENS, 0) + safeTokens
        prefs.edit()
            .putInt(KEY_DAILY_GEMINI_CALLS, calls)
            .putInt(KEY_DAILY_GEMINI_TOKENS, totalTokens)
            .putInt(KEY_LAST_CALL_TOKENS, safeTokens)
            .apply()
    }

    val dailyGeminiCalls: Int
        @Synchronized
        get() {
            ensureTodayLedger()
            return prefs.getInt(KEY_DAILY_GEMINI_CALLS, 0)
        }

    val dailyGeminiTokens: Int
        @Synchronized
        get() {
            ensureTodayLedger()
            return prefs.getInt(KEY_DAILY_GEMINI_TOKENS, 0)
        }

    val lastCallTokens: Int
        @Synchronized
        get() {
            ensureTodayLedger()
            return prefs.getInt(KEY_LAST_CALL_TOKENS, 0)
        }
}
