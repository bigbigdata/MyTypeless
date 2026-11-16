package com.typeless.ime.ai

import android.util.Log
import com.typeless.ime.engine.WhisperHallucinationFilter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
        const val DEFAULT_LANGUAGE = "zh"
        private const val WHISPER_PROMPT = "以下是台灣繁體中文的日常與工作語音逐字稿，請使用繁體中文標點符號，並自然保留句中夾雜的英文專有名詞如 PR, deploy, meeting, sync, API, bug, branch。"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
                .addFormDataPart("language", DEFAULT_LANGUAGE)
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
                val rawText = json.optString("text", "").trim()
                val text = WhisperHallucinationFilter.stripTrailingHallucinations(rawText)
                Log.i(TAG, "Groq STT transcribed successfully (${text.length} chars)")
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq STT network or parsing exception", e)
            Result.failure(e)
        }
    }
}
