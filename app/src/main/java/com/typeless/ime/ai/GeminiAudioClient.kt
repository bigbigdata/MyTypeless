package com.typeless.ime.ai

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * GeminiAudioClient
 * 
 * 負責呼叫 Google Gemini API：
 * 1. [polishText]：純文字智慧去贅字潤飾（核心模式，極省 Token、極速）。
 * 2. [transcribeAndPolish]：多模態音訊直傳潤飾（相容備用）。
 * 
 * 徹底拔除無效的 ListModels 與連環暴擊重試，遇 429 立即終止以保護配額。
 */
class GeminiAudioClient(private val apiKeyProvider: () -> String?) {

    companion object {
        private const val TAG = "GeminiAudioClient"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val SYSTEM_PROMPT = """你是一個極速、精準的逐字稿潤飾與文法排版引擎。輸入為語音辨識輸出的逐字稿（可能是純中文、純英文或中英夾雜說話）。
請嚴格遵循以下核心規範輸出：

1. 【繁體中文規範】：所有中文輸出必須一律強制使用「繁體中文（正體中文，台灣習慣）」，絕對嚴禁輸出任何簡體中文！
2. 【文字鏡像原則（Immutable Tokens，絕對禁止翻譯）】：
   - 輸入中出現的任何英文字元（包含日常單字、名詞、動詞、形容詞、品牌名稱、片語如 PR, deploy, meeting, sync, check, brunch, chill 等）一律視為「不可變更的固定記號」。
   - 說中文就輸出中文，說英文就輸出英文。絕對嚴禁將任何英文單字或片語意譯或翻譯為中文同義詞！必須原汁原味精確保留原文與慣用大小寫。
3. 【語篇結構分段門檻（自然流暢，嚴禁切碎）】：
   - 【預設行文連貫】：正常的一句話或口語連接（包含「還有」、「不過」、「但是」、「而且」等），一律以正常標點符號連接為自然段落，絕對禁止看到連接詞就強行換行！
   - 【顯式序列條列】：僅當口述包含明確的序列詞（例如「第一、... 第二、...」、「1. ... 2. ...」）或條列清單時，才換行整理成清晰的條列格式。
   - 【主題切換分段】：僅在使用者完整陳述完一個想法（通常大於 2~3 句完整句子），且接下來開始探討全然不同的大主題時，才插入空行（\n\n）分段。
   - 【簡短短語維持單行】：日常簡短對話或指令維持單行輸出。
4. 【去除贅字口語】：
   - 僅去除口語贅字與停頓填補詞（例如：呃、啊、那個、就是說、然後其實、嗯等）。
   - 修順句子文法，適當補上正確繁體標點符號（，、。！？）。
5. 【示範範例 (Few-Shot Examples)】：
   - 輸入：那個明天早上 meeting 要記得 review PR 然後 deploy 到 production
     輸出：明天早上 meeting 要記得 review PR，然後 deploy 到 production。
   - 輸入：這週末要不要去吃個 brunch 順便 chill 一下
     輸出：這週末要不要去吃個 brunch，順便 chill 一下。
   - 輸入：呃 就是說 其實我覺得 這個方向可以再調整一下
     輸出：其實我覺得這個方向可以再調整一下。
6. 【絕對禁止意譯】：
   - 不要用你自己的方式重新表達整句話的意思！這不是總結，保留使用者的原話語意與口氣。
7. 【輸出格式】：
   - 僅直接輸出潤飾與排版後的純文字內容。
   - 嚴禁包含任何引號、問候語、Markdown 程式碼區塊標記（```）或多餘解釋。"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * 連線池非同步預熱（在使用者按下說話鍵時觸發）
     */
    fun prewarmConnection() {
        val apiKey = apiKeyProvider() ?: return
        thread(start = true, name = "GeminiPrewarmThread") {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val req = Request.Builder().url(url).head().build()
                client.newCall(req).execute().close()
                Log.d(TAG, "Gemini 連線池預熱完成")
            } catch (_: Exception) {
                // 預熱失敗不影響後續正式調用
            }
        }
    }

    /**
     * 【純文字潤飾模式】（推薦主力）
     * 將 Groq 或本機 STT 轉出的原始逐字稿丟給 Gemini 去贅字
     */
    fun polishText(rawText: String, preferredModel: String? = null): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("尚未設定 Gemini API Key"))
        }

        if (rawText.isBlank()) {
            return Result.success("")
        }

        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        val jsonPayload = buildTextRequestJson(rawText)

        return try {
            val polished = executeCallWithRetry(targetModel, apiKey, jsonPayload)
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "Gemini 配額已滿 (HTTP 429)，立即終止調用以保護配額: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini 文字潤飾異常", e)
            Result.failure(e)
        }
    }

    /**
     * 【多模態音訊直傳模式】（相容備用）
     */
    fun transcribeAndPolish(audioFile: File, preferredModel: String? = null): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("尚未設定 Gemini API Key"))
        }

        if (!audioFile.exists() || audioFile.length() <= 44) {
            return Result.failure(IllegalArgumentException("音訊檔案為空或說話時間過短"))
        }

        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        return try {
            val audioBase64 = encodeFileToBase64(audioFile)
            val jsonPayload = buildAudioRequestJson(audioBase64)
            val polished = executeCallWithRetry(targetModel, apiKey, jsonPayload)
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "Gemini 配額已滿 (HTTP 429)，立即終止以保護配額: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini 音訊直傳處理異常", e)
            Result.failure(e)
        }
    }

    private fun executeCallWithRetry(model: String, apiKey: String, jsonPayload: String): String {
        try {
            return executeApiCall(model, apiKey, jsonPayload)
        } catch (e: ServerUnavailableException) {
            Log.w(TAG, "Gemini 伺服器繁忙 (503)，等待 1 秒後進行唯一一次重試...")
            Thread.sleep(1000)
            return executeApiCall(model, apiKey, jsonPayload)
        }
    }

    private fun executeApiCall(model: String, apiKey: String, jsonPayload: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorSummary = try {
                    val errJson = JSONObject(responseBody).optJSONObject("error")
                    errJson?.optString("message", responseBody) ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }

                if (response.code == 429) {
                    throw RateLimitException("HTTP 429: $errorSummary")
                } else if (response.code == 503) {
                    throw ServerUnavailableException("HTTP 503: $errorSummary")
                }
                throw IOException("HTTP ${response.code}: $errorSummary")
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("API 未回傳文字候選結果: $responseBody")
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                throw IOException("API 回傳內容空白")
            }

            val text = parts.getJSONObject(0).optString("text", "").trim()
            return cleanOutput(text)
        }
    }

    private fun buildTextRequestJson(rawText: String): String {
        val root = JSONObject()

        // System Instruction
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", SYSTEM_PROMPT))
            })
        }
        root.put("system_instruction", systemInstruction)

        // Contents (Pure text prompt)
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()

        partsArray.put(JSONObject().put("text", "請依指示潤飾以下語音轉文字逐字稿並直接輸出潤飾文字：\n\n$rawText"))

        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        root.put("contents", contentsArray)

        // Generation Config
        val genConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", 4096)
        }
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    private fun buildAudioRequestJson(audioBase64: String): String {
        val root = JSONObject()

        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", SYSTEM_PROMPT))
            })
        }
        root.put("system_instruction", systemInstruction)

        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()

        val audioPart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", audioBase64)
            })
        }
        partsArray.put(audioPart)
        partsArray.put(JSONObject().put("text", "請依指示辨識此語音並直接輸出繁體潤飾後文字："))

        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        root.put("contents", contentsArray)

        val genConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", 2048)
        }
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    private fun encodeFileToBase64(file: File): String {
        val bytes = ByteArray(file.length().toInt())
        FileInputStream(file).use { it.read(bytes) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.substring(3, text.length - 3).trim()
            if (text.startsWith("markdown\n") || text.startsWith("text\n")) {
                text = text.substring(text.indexOf('\n') + 1).trim()
            }
        }
        return text
    }

    class RateLimitException(message: String) : IOException(message)
    class ServerUnavailableException(message: String) : IOException(message)
}
