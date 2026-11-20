package com.typeless.ime.ai

import android.util.Base64
import android.util.Log
import com.typeless.ime.BuildConfig
import com.typeless.ime.engine.WhisperHallucinationFilter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GeminiAudioClient
 * 
 * Handles calls to the Google Gemini API:
 * 1. [polishText]: Pure-text intelligent filler removal, homophone correction, and grammar polish.
 * 2. [transcribeAndPolish]: Multimodal direct audio transcription and polish (fallback mode).
 * 
 * Implements a 3-Tier Free Tier Model Cascade:
 *   Tier 1: gemini-3.5-flash-lite (500 RPD / 15 RPM, ~0.7s, high 3.5-gen accuracy, native 0 thinking tokens)
 *   Tier 2: gemini-3-flash-preview (20 RPD / 5 RPM, high accuracy, thinkingBudget=0)
 *   Tier 3: gemini-3.1-flash-lite (500 RPD / 15 RPM, separate 500/day pool, thinkingBudget=0)
 */
class GeminiAudioClient(
    private val apiKeyProvider: () -> String?,
    private val onUsageRecorded: ((Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiAudioClient"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val TIER_2_MODEL = "gemini-3-flash-preview"
        const val FALLBACK_LITE_MODEL = "gemini-3.1-flash-lite"
        val MODEL_CASCADE = listOf(DEFAULT_MODEL, TIER_2_MODEL, FALLBACK_LITE_MODEL)

        internal const val MAX_INJECTED_VOCAB_TERMS = 30
        internal const val THINKING_BUDGET = 0
        internal const val MAX_OUTPUT_TOKENS = 1024
        const val RPM_COOLDOWN_MS = 60_000L // 60s cooldown on 1st 429 (per-minute RPM limit)
        const val TRANSIENT_COOLDOWN_MS = 60_000L // 60s cooldown on HTTP 503 or single-model timeout
        const val RPD_COOLDOWN_MS = 2 * 60 * 60 * 1000L // 2-hour cooldown on repeated 429 (daily RPD exhausted)
        const val RATE_LIMIT_COOLDOWN_MS = RPM_COOLDOWN_MS

        fun friendlyModelBadge(model: String): String {
            return when {
                model.contains("3.5-flash-lite") -> "3.5-Lite"
                model.contains("3-flash") -> "3-Flash"
                model.contains("3.1-flash-lite") -> "3.1-Lite"
                else -> "Gemini"
            }
        }

        internal fun requiresExplicitThinkingBudgetZero(model: String): Boolean {
            // gemini-3.5-flash-lite natively runs with 0 thinking tokens and rejects thinkingBudget with HTTP 400.
            // gemini-3-flash-preview and gemini-3.1-flash-lite require thinkingBudget = 0.
            return !model.startsWith("gemini-3.5")
        }

        private val fillerPrefixRegex = Regex("^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)[，,。？?\\s]*")
        private val inlineFillerRegex = Regex("([，,]\\s*(呃|啊|那個|就是說|嗯)\\s*)")

        private const val SYSTEM_PROMPT = """你是一個極速、精準的台灣繁體中文與中英雙語逐字稿校對與排版引擎。輸入為語音辨識（STT）輸出的逐字稿，可能包含同音錯字或口語贅詞。
請嚴格遵循以下核心規範輸出：

1. 【台灣繁體中文規範】：所有中文輸出必須一律強制使用「台灣習慣正體中文（繁體中文）」，絕對嚴禁輸出任何簡體字！
2. 【語音辨識同音錯字主動修復與專有名詞校準】：
   - 語音辨識（STT）常因聲學同音或近音而產生不合上下文語境的錯別字（無論是短句或超過百字的長篇演講）。請務必根據整段話的前後文脈絡，主動將語意不通的同音錯字修正為自然、正確的台灣繁體中文詞彙！
   - 【高頻同音字語境校準】：
     * 「在 / 再」：動作重複、延續、接在動詞前或表示「下一次」時強制用「再」（如：「再次確認」、「再說一次」、「改天再約」）；表示時間、地點、狀態、正在進行時強制用「在」（如：「在開會」、「在線上」）。
     * 「的 / 得 / 地」：動詞或形容詞後接程度補語用「得」（如：「做得很好」、「跑得很快」）；修飾動詞的狀語用「地」（如：「慢慢地說」）；名詞所有格或定語用「的」。
     * 「做 / 作」：抽象事務、工作、文藝創作、公務用「作」（如：「工作」、「作業」、「作法」、「作決定」）；具體行動、製造用「做」（如：「做簡報」、「做專案」、「做測試」）。
     * 「需 / 須」：動詞或名詞需要、需求用「需」；副詞必須、務必用「須」（如：「必須」、「須知」）。
     * 一律強制使用「部署」（不用布署）、「身分」（不用身份）、「十分鐘」（不用十分鍾）、「複雜 / 複製」（不用復雜 / 復製）。
     * 「紀錄 / 記錄」：名詞結果與文檔用「紀錄」（如：「開會紀錄」）；動詞動作時用「記錄」（如：「記錄下來」）。
   - 【台灣科技、商業與產業專有名詞同音校正】：
     * 「連發客 / 聯發客 / 蓮發科」➔「聯發科」
     * 「灰達 / 回達 / 輝打」➔「輝達」
     * 「紅海」在科技代工語境 ➔「鴻海」
     * 「西制裁 / 圭智財 / 吸智財」➔「矽智財」
     * 「金源代工 / 晶原代工」➔「晶圓代工」
     * 「風測」在半導體語境 ➔「封測」
     * 「次服器」➔「伺服器」；「支安 / 治安」在 IT 語境 ➔「資安」；「計中 / 機防」➔「機房」
3. 【移除語音結尾靜音幻覺】：
   - 若輸入句尾出現與前文語意無關的「謝謝大家」、「謝謝你」、「謝謝觀看」、「感謝收看」、「字幕由...提供」等 Whisper 語音辨識片尾幻覺，請直接移除，切勿保留！
4. 【英文鏡像不翻譯】：
   - 輸入中的任何英文單字或縮寫（如 PR, deploy, meeting, sync, check, branch, API, SDK 等）必須原樣保留並維持正確大小寫，嚴禁翻譯成中文。
5. 【語篇結構分段與雙語智慧條列】：
   - 遇話題轉折詞（另外、除此之外、總之、另一方面、Additionally, Furthermore, In conclusion 等）插入標準空行（\n\n）分段。
   - 遇序數詞（第一/第二/第三、首先/其次/最後、第一個是/第二個是、First/Second/Third）自動轉為編號清單（1. 2. 3.），引導句末加冒號（中文「：」、英文「:」）。
   - 遇無序重點列舉（有幾個重點、Here are the key takeaways 等）轉為減號清單（- 項目）。
   - 一般日常短句維持單行連貫。
6. 【去贅字且保留原意】：
   - 去除口語贅詞（呃、啊、那個、就是說、然後其實、嗯、um, uh, like, you know），修順標點與同音錯字，保留說話者原本的語氣與意思，不要變成摘要總結。
7. 【示範】：
   - 輸入：那個明天早上 meeting 要記得 review PR 然後 deploy 到 production
     輸出：明天早上 meeting 要記得 review PR，然後 deploy 到 production。
   - 輸入：明天灰達跟連發客要在線上開會 我們在討論看看晶原代工的產能分配 謝謝大家
     輸出：明天輝達跟聯發科要在線上開會，我們再討論看看晶圓代工的產能分配。
   - 輸入：今天有三個重點要同步 第一個是 API 規格已經 freeze 了 第二個是 staging 環境已經 deploy 完成 第三個是下週二要上 production
     輸出：今天有三個重點要同步：
1. API 規格已經 freeze。
2. Staging 環境已經 deploy 完成。
3. 下週二要上 production。
8. 【純文字輸出】：僅直接輸出結果，嚴禁加引號、Markdown 程式碼區塊（```）或任何解釋。"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Shares the same connection pool as [client] with longer timeouts for multimodal audio uploads.
    private val audioClient = client.newBuilder()
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    internal var apiCallerOverride: ((model: String, apiKey: String, jsonPayload: String, useAudioTimeouts: Boolean) -> String)? = null

    private val modelCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val modelStrikeCount = ConcurrentHashMap<String, Int>()

    @Volatile
    var lastUsedModel: String = DEFAULT_MODEL
        private set

    /**
     * Evicts idle pooled TCP connections (e.g., after waking from Doze or switching networks)
     * so subsequent requests never stall on a stale half-open socket.
     */
    fun evictIdleConnections() {
        client.connectionPool.evictAll()
    }

    /**
     * Clears all per-model cooldowns and strike counters (e.g., after a manual connection check succeeds).
     */
    fun clearCooldowns() {
        modelCooldownUntilMs.clear()
        modelStrikeCount.clear()
    }

    fun isModelInCooldown(model: String): Boolean {
        val until = modelCooldownUntilMs[model] ?: return false
        return System.currentTimeMillis() < until
    }

    /**
     * Records a temporary congestion/timeout failure (HTTP 503 or socket timeout) for a model.
     * Applies a short 60s cooldown WITHOUT escalating to the 2-hour daily quota cooldown.
     */
    fun recordModelTransientFailure(model: String): Long {
        modelCooldownUntilMs[model] = System.currentTimeMillis() + TRANSIENT_COOLDOWN_MS
        return TRANSIENT_COOLDOWN_MS
    }

    /**
     * Records a 429 rate-limit strike for a specific model:
     * - 1st strike (likely 1-minute RPM spike): 60s cooldown
     * - 2nd+ strike (daily RPD quota exhausted): 2-hour cooldown so subsequent calls skip in 0ms
     */
    fun recordModelRateLimit(model: String, errorMsg: String = ""): Long {
        val strikes = (modelStrikeCount[model] ?: 0) + 1
        modelStrikeCount[model] = strikes
        val isDailyQuotaExhausted = strikes >= 2 || errorMsg.contains("PerDay", ignoreCase = true) || errorMsg.contains("daily", ignoreCase = true)
        val cooldownMs = if (isDailyQuotaExhausted) RPD_COOLDOWN_MS else RPM_COOLDOWN_MS
        modelCooldownUntilMs[model] = System.currentTimeMillis() + cooldownMs
        return cooldownMs
    }

    val isInRateLimitCooldown: Boolean
        get() = MODEL_CASCADE.all { isModelInCooldown(it) }

    private fun orderedCandidateModels(preferredModel: String?): List<String> {
        val primary = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        return (listOf(primary) + MODEL_CASCADE).distinct()
    }

    /**
     * [Text Polish Mode] (Primary recommended mode)
     * Passes the raw transcript from Groq or local STT to Gemini for filler word removal and grammar polish.
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
            return Result.failure(RateLimitException("All Gemini Free Tier models are in cooldown"))
        }

        return try {
            val polished = executeCascadeWithRetry(preferredModel, apiKey) { candidateModel ->
                buildTextRequestJson(rawText, knownVocabulary, candidateModel)
            }
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "All Gemini cascade models hit 429 rate limit, falling back to local formatter: ${e.message}")
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

        return try {
            val audioBase64 = encodeFileToBase64(audioFile)
            val polished = executeCascadeWithRetry(preferredModel, apiKey, useAudioTimeouts = true) { candidateModel ->
                buildAudioRequestJson(audioBase64, knownVocabulary, candidateModel)
            }
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "Gemini quota exceeded (HTTP 429), aborting call to protect quota: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio direct transcription exception", e)
            Result.failure(e)
        }
    }

    /**
     * Live API Contract Verification:
     * Sends a lightweight real POST request to verify that the configured API key and model cascade
     * return HTTP 200 OK on Google's Generative Language API.
     */
    fun verifyApiKeyAndModel(preferredModel: String? = null): Result<String> {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Gemini API Key is not configured"))
        }
        return try {
            val output = executeCascadeWithRetry(preferredModel, apiKey) { candidateModel ->
                buildTextRequestJson("測試連線", emptyList(), candidateModel)
            }
            Result.success("HTTP 200 OK ($lastUsedModel): $output")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeCascadeWithRetry(
        preferredModel: String?,
        apiKey: String,
        useAudioTimeouts: Boolean = false,
        payloadBuilder: (String) -> String
    ): String {
        val candidates = orderedCandidateModels(preferredModel)
        var lastError: Exception? = null

        for (candidateModel in candidates) {
            if (isModelInCooldown(candidateModel)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Skipping model $candidateModel (currently in cooldown)")
                }
                continue
            }

            val jsonPayload = payloadBuilder(candidateModel)
            try {
                val result = apiCallerOverride?.invoke(candidateModel, apiKey, jsonPayload, useAudioTimeouts)
                    ?: executeApiCall(candidateModel, apiKey, jsonPayload, useAudioTimeouts)
                // Call succeeded on candidateModel! Reset its strike count and record active model.
                modelStrikeCount[candidateModel] = 0
                modelCooldownUntilMs.remove(candidateModel)
                lastUsedModel = candidateModel
                return result
            } catch (e: RateLimitException) {
                val cdMs = recordModelRateLimit(candidateModel, e.message ?: "")
                Log.w(TAG, "Model $candidateModel hit HTTP 429 (cooldown ${cdMs / 1000}s); cascading to next model...")
                lastError = e
            } catch (e: ServerUnavailableException) {
                val cdMs = recordModelTransientFailure(candidateModel)
                Log.w(TAG, "Model $candidateModel busy with HTTP 503 (cooldown ${cdMs / 1000}s); cascading to next model...")
                lastError = e
            } catch (e: InterruptedIOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw e
                }
                val cdMs = recordModelTransientFailure(candidateModel)
                Log.w(TAG, "Model $candidateModel timed out (cooldown ${cdMs / 1000}s); cascading to next model...")
                lastError = e
            } catch (e: ModelNotFoundException) {
                // Model is unavailable/deprecated for this project -> cool down for 2 hours and cascade
                modelCooldownUntilMs[candidateModel] = System.currentTimeMillis() + RPD_COOLDOWN_MS
                Log.w(TAG, "Model $candidateModel returned 404; cascading to next model...")
                lastError = e
            }
        }

        throw (lastError ?: RateLimitException("All Gemini models in cascade are currently in cooldown"))
    }

    private fun executeApiCall(
        model: String,
        apiKey: String,
        jsonPayload: String,
        useAudioTimeouts: Boolean = false
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        val httpClient = if (useAudioTimeouts) audioClient else client
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorSummary = try {
                    val errJson = JSONObject(responseBody).optJSONObject("error")
                    errJson?.optString("message", responseBody) ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }

                if (response.code == 404) {
                    throw ModelNotFoundException("HTTP 404: $errorSummary")
                } else if (response.code == 429) {
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
        var text = WhisperHallucinationFilter.stripTrailingHallucinations(rawText).trim()
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

    internal fun buildTextRequestJson(
        rawText: String,
        knownVocabulary: List<String>? = null,
        model: String = DEFAULT_MODEL
    ): String {
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

        // Generation Config:
        // - gemini-3.5-flash-lite natively runs with 0 thinking tokens and rejects thinkingBudget with HTTP 400
        // - gemini-3-flash-preview and gemini-3.1-flash-lite require thinkingBudget = 0
        val genConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            if (requiresExplicitThinkingBudgetZero(model)) {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", THINKING_BUDGET)
                })
            }
        }
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    internal fun buildAudioRequestJson(
        audioBase64: String,
        knownVocabulary: List<String>? = null,
        model: String = DEFAULT_MODEL
    ): String {
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
            if (requiresExplicitThinkingBudgetZero(model)) {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 0)
                })
            }
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
        return WhisperHallucinationFilter.stripTrailingHallucinations(text)
    }

    class RateLimitException(message: String) : IOException(message)
    class ServerUnavailableException(message: String) : IOException(message)
    class ModelNotFoundException(message: String) : IOException(message)
}
