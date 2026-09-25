package com.typeless.ime.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * GroqWhisperClient
 * 
 * Sends recorded audio files (WAV/AAC) to the Groq Whisper API for ultra-fast Speech-to-Text (STT).
 * Powered by whisper-large-v3-turbo, supporting accurate code-switching and technical terminology.
 */
class GroqWhisperClient(private val apiKeyProvider: () -> String?) {

    companion object {
        private const val TAG = "GroqWhisperClient"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_NAME = "whisper-large-v3-turbo"
        private const val WHISPER_PROMPT = "這是一段日常生活與工作的繁體中文語音，自然夾雜常見的英文單字與專有名詞，例如：PR, deploy, meeting, sync, check, chill, brunch, bug, commit, branch, merge, feature, API, SDK, PM, UI, UX, issue, release, test, Wi-Fi, Google, GitHub, Notion, Slack。"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * Asynchronously pre-warms the connection pool when the user starts speaking.
     * Establishes a TLS session in advance to eliminate 150-250ms of handshake latency upon release.
     */
    fun prewarmConnection() {
        val apiKey = apiKeyProvider() ?: return
        thread(start = true, name = "GroqPrewarmThread") {
            try {
                val req = Request.Builder()
                    .url(GROQ_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .head()
                    .build()
                client.newCall(req).execute().close()
                Log.d(TAG, "Groq connection pool pre-warmed successfully")
            } catch (_: Exception) {
                // Pre-warming failure is non-fatal; regular calls will establish connection as normal
            }
        }
    }

    /**
     * Transcribes an audio file into raw text (supports M4A/AAC and WAV).
     *
     * @param audioFile The recorded audio file.
     * @param customPrompt Optional dynamic vocabulary prompt to bias Whisper decoding.
     */
    fun transcribe(audioFile: File, customPrompt: String? = null): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Groq API Key is not configured"))
        }

        if (!audioFile.exists() || audioFile.length() <= 100) {
            return Result.failure(IllegalArgumentException("Audio file is empty or recording duration was too short"))
        }

        val promptToUse = customPrompt?.takeIf { it.isNotBlank() } ?: WHISPER_PROMPT

        return try {
            val mimeString = if (audioFile.name.endsWith(".m4a", ignoreCase = true)) {
                "audio/m4a"
            } else {
                "audio/wav"
            }
            val audioMediaType = mimeString.toMediaType()
            val fileRequestBody = audioFile.asRequestBody(audioMediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileRequestBody)
                .addFormDataPart("model", MODEL_NAME)
                .addFormDataPart("response_format", "json")
                .addFormDataPart("temperature", "0.0")
                .addFormDataPart("language", "zh")
                .addFormDataPart("prompt", promptToUse)
                .build()

            val request = Request.Builder()
                .url(GROQ_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message", body) ?: body
                    } catch (_: Exception) {
                        body
                    }
                    Log.e(TAG, "Groq STT failed (HTTP ${response.code}): $errMsg")
                    return Result.failure(IOException("Groq HTTP ${response.code}: $errMsg"))
                }

                val json = JSONObject(body)
                val text = json.optString("text", "").trim()
                Log.i(TAG, "Groq STT transcribed successfully: $text")
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq STT network or parsing exception", e)
            Result.failure(e)
        }
    }
}
