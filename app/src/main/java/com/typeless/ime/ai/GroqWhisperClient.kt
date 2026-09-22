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

/**
 * GroqWhisperClient
 * 
 * 負責將錄製的語音檔案（WAV/AAC）發送給 Groq Whisper API 進行極速語音轉文字（STT）。
 * 模型採用 whisper-large-v3-turbo，支援中英混雜與專有名詞精準轉錄。
 */
class GroqWhisperClient(private val apiKeyProvider: () -> String?) {

    companion object {
        private const val TAG = "GroqWhisperClient"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_NAME = "whisper-large-v3-turbo"
        private const val WHISPER_PROMPT = "這是一段日常生活與對話的繁體中文語音，內容自然夾雜一些常見的 English words、品牌名稱與專有名詞。"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * 將語音檔案轉錄為原始文字（支援 M4A/AAC 與 WAV）
     */
    fun transcribe(audioFile: File): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("尚未設定 Groq API Key"))
        }

        if (!audioFile.exists() || audioFile.length() <= 100) {
            return Result.failure(IllegalArgumentException("音訊檔案為空或錄音時間過短"))
        }

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
                .addFormDataPart("prompt", WHISPER_PROMPT)
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
                    Log.e(TAG, "Groq STT 失敗 (HTTP ${response.code}): $errMsg")
                    return Result.failure(IOException("Groq HTTP ${response.code}: $errMsg"))
                }

                val json = JSONObject(body)
                val text = json.optString("text", "").trim()
                Log.i(TAG, "Groq STT 成功轉錄: $text")
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq STT 網路或解析異常", e)
            Result.failure(e)
        }
    }
}
