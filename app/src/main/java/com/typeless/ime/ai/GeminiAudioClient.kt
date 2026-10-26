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
class GeminiAudioClient(private val apiKeyProvider: () -> String?) {

    companion object {
        private const val TAG = "GeminiAudioClient"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val SYSTEM_PROMPT = """你是一個極速、精準的逐字稿潤飾與文法排版引擎。輸入為語音辨識輸出的逐字稿（可能是純中文、純英文或中英夾雜說話）。
請嚴格遵循以下核心規範輸出：

1. 【繁體中文規範】：所有中文輸出必須一律強制使用「繁體中文（正體中文，台灣習慣）」，絕對嚴禁輸出任何簡體中文！
2. 【繁體中文語境同音字與專有名詞校正規範】：
   - 【高頻同音字語境校準】：
     * 「在 / 再」：動作重複、延續、接在動詞前或表示「下一次」時強制用「再」（例如：「再次確認」、「再說一次」、「改天再約」、「再考慮」）；表示時間、地點、狀態、正在進行時強制用「在」（例如：「在開會」、「在線上」、「在台北」、「在進行中」）。
     * 「的 / 得 / 地」：動詞或形容詞後接程度補語時強制用「得」（例如：「做得很好」、「跑得很快」、「顯得專業」）；修飾動詞的狀語用「地」（例如：「慢慢地說」、「認真地做」）；名詞所有格或定語用「的」（例如：「我的專案」、「重要的事情」）。
     * 「做 / 作」：抽象事務、工作、文藝創作、公務用「作」（例如：「工作」、「作業」、「作法」、「作為」、「作決定」）；具體行動、製造、手動操作用「做」（例如：「做簡報」、「做專案」、「做測試」）。
     * 「需 / 須」：動詞或名詞需要、需求用「需」（例如：「需求」、「需要」）；副詞必須、務必用「須」（例如：「必須」、「須知」）。
     * 「部署 / 布署」：軟體、系統與雲端上線架構一律強制使用「部署」（deploy）。
     * 「紀錄 / 記錄」：名詞結果與文檔用「紀錄」（例如：「開會紀錄」、「最高紀錄」）；動詞記錄動作時用「記錄」（例如：「記錄下來」）。
     * 「身分 / 身份」：台灣標準用語一律使用「身分」（例如：「身分證」、「身分驗證」）。
   - 【台灣在地科技、商業與產業專有名詞聲學校正】：
     語音辨識因聲學同音常產生荒謬字詞，若語境涉及科技、商業、半導體或工程，請強制校正：
     * 「連發客 / 聯發客 / 蓮發科」➔「聯發科」
     * 「灰達 / 回達 / 輝打」➔「輝達」
     * 「紅海」若在科技代工/組裝語境 ➔「鴻海」
     * 「西制裁 / 圭智財 / 吸智財」➔「矽智財」
     * 「金源代工 / 晶原代工」➔「晶圓代工」
     * 「風測」在半導體/晶片語境 ➔「封測」
     * 「次服器」➔「伺服器」
     * 「支安 / 治安」在 IT、資訊安全語境 ➔「資安」
     * 「計中 / 機防」在機房語境 ➔「機房」
3. 【文字鏡像原則（Immutable Tokens，絕對禁止翻譯）】：
   - 輸入中出現的任何英文字元（包含日常單字、名詞、動詞、形容詞、品牌名稱、片語如 PR, deploy, meeting, sync, check, brunch, chill 等）一律視為「不可變更的固定記號」。
   - 說中文就輸出中文，說英文就輸出英文。絕對嚴禁將任何英文單字或片語意譯或翻譯為中文同義詞！必須原汁原味精確保留原文與慣用大小寫。
4. 【語篇結構分段與雙語智慧條列規範】：
   - 【主題切換與段落空行（\n\n）】：
     當語意出現明顯話題躍遷、視角切換或轉折詞時，必須插入標準空行（\n\n）分成獨立段落：
     * 中文轉折詞：例如「另外...」、「除此之外...」、「關於下一點...」、「總之/總結來說...」、「另一方面...」等。
     * 英文轉折詞：例如 "Additionally, ...", "Furthermore, ...", "On the other hand, ...", "Regarding [topic], ...", "In conclusion, ...", "Moving on to..." 等。
   - 【序號條列清單（1. 2. 3.）】：
     口述中包含明確先後順序或序數詞時，自動轉換為編號清單（1. 2. 3. ），並自動去除「第一個是」、「Number one is」等口語贅詞：
     * 中文序列詞：例如「第一、第二、第三」、「首先、其次、最後」、「第一個是、第二個是」等。
     * 英文序列詞：例如 "First, ... Second, ... Third, ...", "First of all, ... Next, ... Finally, ...", "Number one, ... Number two, ..." 等。
   - 【無序重點清單（- 項目）】：
     口述列舉重點、待辦或多個要點（無明確序號）時，自動轉換為 Markdown 減號清單（- 項目）：
     * 中文引導：例如「有幾個重點」、「包含以下項目」、「有幾點注意事項」等。
     * 英文引導：例如 "Here are the key points:", "Action items include:", "Key takeaways:" 等。
     * 條列各項目英文開頭字母請大寫。
   - 【清單引導句冒號規範】：
     清單前置引導句末尾自動附上正確冒號：中文前方用全形冒號「：」，英文前方用半形冒號加空格「: 」。
   - 【簡短短語維持單行】：
     日常簡短對話或單一指令（無論中英文）維持單行連貫輸出，不切碎。
5. 【去除贅字口語】：
   - 僅去除口語贅字與停頓填補詞（例如：呃、啊、那個、就是說、然後其實、嗯、like, you know, um, uh 等）。
   - 修順句子文法，適當補上正確標點符號。
6. 【示範範例 (Few-Shot Examples)】：
   - 範例 1（簡短中英日常）：
     輸入：那個明天早上 meeting 要記得 review PR 然後 deploy 到 production
     輸出：明天早上 meeting 要記得 review PR，然後 deploy 到 production。
   - 範例 2（中文同音字與科技專有名詞校正）：
     輸入：明天灰達跟連發客要在線上開會 我們再討論看看晶原代工的產能分配
     輸出：明天輝達跟聯發科要在線上開會，我們再討論看看晶圓代工的產能分配。
   - 範例 3（中文序號條列）：
     輸入：今天有三個重點要同步 第一個是 API 規格已經 freeze 了 第二個是 staging 環境已經 deploy 完成 第三個是下週二要上 production
     輸出：今天有三個重點要同步：
     1. API 規格已經 freeze。
     2. Staging 環境已經 deploy 完成。
     3. 下週二要上 production。
   - 範例 4（英文序號條列）：
     輸入：we have three action items today first review the PR second deploy to staging third run benchmark
     輸出：We have three action items today:
     1. Review the PR.
     2. Deploy to staging.
     3. Run benchmark.
   - 範例 5（中英混用多段落轉折）：
     輸入：目前這個 issue 我們已經在 staging 驗證過了 基本上沒什麼問題 另外 關於下週一的 sprint planning 我們預計會把重心放在重構 API 模組
     輸出：目前這個 issue 我們已經在 staging 驗證過了，基本上沒什麼問題。

     另外，關於下週一的 sprint planning，我們預計會把重心放在重構 API 模組。
   - 範例 6（英文無序重點清單）：
     輸入：here are the key takeaways from the discussion we fixed the memory leak and resolved connection timeout
     輸出：Here are the key takeaways:
     - Fixed the memory leak
     - Resolved connection timeout
   - 範例 7（英文轉折多段落）：
     輸入：the backend release is scheduled for tomorrow morning everything looks stable additionally please notify the mobile team once it is deployed
     輸出：The backend release is scheduled for tomorrow morning, everything looks stable.

     Additionally, please notify the mobile team once it is deployed.
7. 【絕對禁止意譯】：
   - 不要用你自己的方式重新表達整句話的意思！這不是總結，保留使用者的原話語意與口氣。
8. 【輸出格式】：
   - 僅直接輸出潤飾與排版後的純文字內容。
   - 嚴禁包含任何引號、問候語、Markdown 程式碼區塊標記（```）或多餘解釋。"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Asynchronously pre-warms the connection pool when the user starts speaking.
     */
    fun prewarmConnection() {
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

        val targetModel = preferredModel?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        val jsonPayload = buildTextRequestJson(rawText, knownVocabulary)

        return try {
            val polished = executeCallWithRetry(targetModel, apiKey, jsonPayload)
            Result.success(polished)
        } catch (e: RateLimitException) {
            Log.w(TAG, "Gemini quota exceeded (HTTP 429), aborting call to protect quota: ${e.message}")
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
     * Composes the system prompt, dynamically appending custom vocabulary and fuzzy homophone
     * restoration guidelines if known terms are supplied.
     */
    private fun buildSystemPrompt(knownVocabulary: List<String>? = null): String {
        if (knownVocabulary.isNullOrEmpty()) {
            return SYSTEM_PROMPT
        }
        val vocabListStr = knownVocabulary.joinToString(", ")
        return """$SYSTEM_PROMPT

9. 【使用者專屬詞庫與雙語模糊校正規範】：
   - 以下為使用者高頻累積與自訂的標準專有名詞清單（包含英文術語與繁體中文專有詞彙）：
     [$vocabListStr]
   - 若逐字稿中出現發音或語意高度疑似清單中詞彙的同音錯字、口誤或被誤聽為同音漢字（例如：「底坡」還原為「deploy」、「批啊」還原為「PR」、「插可」還原為「check」、「不讓取」還原為「branch」、「蓮發科」還原為「聯發科」、「灰達」還原為「輝達」），請優先還原為清單中的標準專有名詞型態與標準大小寫。
   - 若上下文語意完全無關，則絕對不要生硬置換。"""
    }

    private fun buildTextRequestJson(rawText: String, knownVocabulary: List<String>? = null): String {
        val root = JSONObject()

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

    private fun buildAudioRequestJson(audioBase64: String, knownVocabulary: List<String>? = null): String {
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
