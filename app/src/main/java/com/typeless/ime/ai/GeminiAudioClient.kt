package com.typeless.ime.ai

import android.util.Base64
import android.util.Log
import com.typeless.ime.BuildConfig
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
 * Handles calls to the Google Gemini API:
 * 1. [polishText]: Pure-text intelligent filler removal and grammar polish (primary recommended mode, token-efficient and fast).
 * 2. [transcribeAndPolish]: Multimodal direct audio transcription and polish (fallback mode).
 * 
 * Eliminates redundant ListModels queries and aggressive retry loops, terminating immediately on 429 to safeguard quota.
 */
class GeminiAudioClient(
    private val apiKeyProvider: () -> String?,
    private val onUsageRecorded: ((Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiAudioClient"
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        internal const val MAX_INJECTED_VOCAB_TERMS = 30
        internal const val THINKING_BUDGET = 0
        internal const val MAX_OUTPUT_TOKENS = 1024
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L

        private val fillerPrefixRegex = Regex("^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)[，,。？?\\s]*")
        private val inlineFillerRegex = Regex("([，,]\\s*(呃|啊|那個|就是說|嗯)\\s*)")

        private const val SYSTEM_PROMPT = """你是極速繁體中文與中英雙語逐字稿潤飾引擎。請嚴格遵守：
1. 【台灣繁體中文】：一律輸出台灣習慣繁體中文，嚴禁簡體字。
2. 【同音字與科技詞校正】：
   - 依語境區分：在/再、的/得/地、做/作、需/須、紀錄/記錄，一律用「部署」、「身分」。
   - 科技同音校正：聯發科、輝達、鴻海、矽智財、晶圓代工、封測、伺服器、資安、機房。
3. 【英文鏡像不翻譯】：輸入中的任何英文單字或縮寫（如 PR, deploy, meeting, sync, check, branch 等）必須原樣保留並維持正確大小寫，嚴禁翻譯成中文。
4. 【智慧分段與條列】：
   - 遇話題轉折詞（另外、除此之外、總之、Additionally, Furthermore, In conclusion 等）插入空行（\n\n）分段。
   - 遇序數詞（第一/第二/第三、首先/其次/最後、First/Second/Third）轉為編號清單（1. 2. 3.），引導句末加冒號（中文「：」、英文「:」）。
   - 遇多項重點列舉（有幾個重點、Here are the key takeaways 等）轉為減號清單（- 項目）。
   - 一般日常短句維持單行連貫。
5. 【去贅字且禁意譯】：僅去除口語贅詞（呃、啊、那個、就是說、嗯、um, uh, like, you know），修順標點，保留原意與語氣，嚴禁改寫或總結。
6. 【示範】：
   - 輸入：那個明天早上 meeting 要記得 review PR 然後 deploy 到 production
     輸出：明天早上 meeting 要記得 review PR，然後 deploy 到 production。
   - 輸入：今天有三個重點要同步 第一個是 API 規格已經 freeze 了 第二個是 staging 環境已經 deploy 完成 第三個是下週二要上 production
     輸出：今天有三個重點要同步：
1. API 規格已經 freeze。
2. Staging 環境已經 deploy 完成。
3. 下週二要上 production。
7. 【純文字輸出】：僅直接輸出結果，嚴禁加引號、Markdown 程式碼區塊（```）或任何解釋。"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var rateLimitCooldownUntilMs: Long = 0L

    val isInRateLimitCooldown: Boolean
        get() = System.currentTimeMillis() < rateLimitCooldownUntilMs

    /**
     * Asynchronously pre-warms the connection pool when the user starts speaking.
     */
    fun prewarmConnection() {
        if (isInRateLimitCooldown) return
        val apiKey = apiKeyProvider() ?: return
        thread(start = true, name = "GeminiPrewarmThread") {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models"
                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .head()
                    .build()
                client.newCall(req).execute().close()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Gemini connection pool pre-warmed successfully")
                }
            } catch (_: Exception) {
                // Pre-warming failure is non-fatal
            }
        }
    }

    /**
     * [Text Polish Mode] (Primary recommended mode)
     * Passes the raw transcript from Groq or local STT to Gemini for filler word removal and grammar polish.
     *
     * @param rawText The raw transcript from STT.
     * @param preferredModel Preferred Gemini model ID.
     * @param knownVocabulary List of custom user vocabulary and technical terms for homophone correction.
     */
    fun polishText(
        rawText: String,
        preferredModel: String? = null,
        knownVocabulary: List<String>? = null
    ): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Gemini API Key is not configured"))
        }

        if (rawText.isBlank()) {
            return Result.success("")
        }

        if (isInRateLimitCooldown) {
            return Result.failure(RateLimitException("Gemini Free Tier 429 cooldown active"))
        }

        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        val jsonPayload = buildTextRequestJson(rawText, knownVocabulary)

        return try {
            val polished = executeCallWithRetry(targetModel, apiKey, jsonPayload)
            Result.success(polished)
        } catch (e: RateLimitException) {
            rateLimitCooldownUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            Log.w(TAG, "Gemini quota exceeded (HTTP 429), activating 60s local fallback cooldown: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini text polishing exception", e)
            Result.failure(e)
        }
    }

    /**
     * [Multimodal Direct Audio Mode] (Fallback mode)
     */
    fun transcribeAndPolish(
        audioFile: File,
        preferredModel: String? = null,
        knownVocabulary: List<String>? = null
    ): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Gemini API Key is not configured"))
        }

        if (!audioFile.exists() || audioFile.length() <= 44) {
            return Result.failure(IllegalArgumentException("Audio file is empty or recording duration was too short"))
        }

        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        return try {
            val audioBase64 = encodeFileToBase64(audioFile)
            val jsonPayload = buildAudioRequestJson(audioBase64, knownVocabulary)
            val polished = executeCallWithRetry(targetModel, apiKey, jsonPayload)
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "Gemini quota exceeded (HTTP 429), aborting call to protect quota: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio direct transcription exception", e)
            Result.failure(e)
        }
    }

    private fun executeCallWithRetry(model: String, apiKey: String, jsonPayload: String): String {
        try {
            return executeApiCall(model, apiKey, jsonPayload)
        } catch (e: ServerUnavailableException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Gemini server busy (503), waiting 1s before retrying once...")
            }
            Thread.sleep(1000)
            return executeApiCall(model, apiKey, jsonPayload)
        }
    }

    private fun executeApiCall(model: String, apiKey: String, jsonPayload: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
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
            val usage = jsonResponse.optJSONObject("usageMetadata")
            if (usage != null) {
                val promptToks = usage.optInt("promptTokenCount", 0)
                val candToks = usage.optInt("candidatesTokenCount", 0)
                val thoughtToks = usage.optInt("thoughtsTokenCount", 0)
                val totalToks = usage.optInt("totalTokenCount", promptToks + candToks + thoughtToks)
                onUsageRecorded?.invoke(totalToks)
            } else {
                onUsageRecorded?.invoke(0)
            }

            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("API returned no candidate text: $responseBody")
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                throw IOException("API returned empty content")
            }

            val text = parts.getJSONObject(0).optString("text", "").trim()
            return cleanOutput(text)
        }
    }

    /**
     * Pre-cleans leading/inline filler words locally to reduce input token count before calling Gemini.
     */
    internal fun preCleanRawText(rawText: String): String {
        var text = rawText.trim()
        var prev = ""
        while (text != prev) {
            prev = text
            text = fillerPrefixRegex.replaceFirst(text, "").trim()
        }
        return inlineFillerRegex.replace(text, " ").trim()
    }

    /**
     * Composes the system prompt, dynamically appending custom vocabulary and fuzzy homophone
     * restoration guidelines if known terms are supplied (strictly capped at MAX_INJECTED_VOCAB_TERMS).
     */
    internal fun buildSystemPrompt(knownVocabulary: List<String>? = null): String {
        val cappedVocab = knownVocabulary?.filter { it.isNotBlank() }?.take(MAX_INJECTED_VOCAB_TERMS)
        if (cappedVocab.isNullOrEmpty()) {
            return SYSTEM_PROMPT
        }
        val vocabListStr = cappedVocab.joinToString(", ")
        return """$SYSTEM_PROMPT
8. 【專屬詞庫同音校正】：[$vocabListStr]。若出現發音高度相似的同音誤字（如「底坡」➔「deploy」、「批啊」➔「PR」、「蓮發科」➔「聯發科」），請依語境還原為標準詞彙與大小寫。"""
    }

    internal fun buildTextRequestJson(rawText: String, knownVocabulary: List<String>? = null): String {
        val root = JSONObject()
        val cleanedInput = preCleanRawText(rawText).ifBlank { rawText.trim() }

        // System Instruction
        val promptText = buildSystemPrompt(knownVocabulary)
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", promptText))
            })
        }
        root.put("system_instruction", systemInstruction)

        // Contents (Pure text prompt)
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()

        partsArray.put(JSONObject().put("text", "潤飾逐字稿：\n$cleanedInput"))

        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        root.put("contents", contentsArray)

        // Generation Config (strictly disable Thinking tokens to prevent $2.50/1M reasoning charges)
        val genConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            put("thinkingConfig", JSONObject().apply {
                put("thinkingBudget", THINKING_BUDGET)
            })
        }
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    internal fun buildAudioRequestJson(audioBase64: String, knownVocabulary: List<String>? = null): String {
        val root = JSONObject()

        val promptText = buildSystemPrompt(knownVocabulary)
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", promptText))
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
            put("maxOutputTokens", 1024)
            put("thinkingConfig", JSONObject().apply {
                put("thinkingBudget", 0)
            })
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
