package com.typeless.ime.engine

import android.database.sqlite.SQLiteDatabase
import com.typeless.ime.SettingsManager
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.ai.GroqWhisperClient
import com.typeless.ime.vocabulary.VocabularyDatabaseHelper
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSemanticFormatterTest {

    @Test
    fun testNumberedListZh() {
        val input = "今天有三個重點要同步 第一個是 API 規格已經 freeze 了 第二個是 staging 環境已經 deploy 完成 第三個是下週二要上 production"
        val vocab = listOf("API", "freeze", "staging", "deploy", "production")
        val output = LocalSemanticFormatter.format(input, vocab)
        
        assertTrue(output.contains("今天有三個重點要同步："))
        assertTrue(output.contains("1. API 規格已經 freeze 了。"))
        assertTrue(output.contains("2. Staging 環境已經 deploy 完成。") || output.contains("2. staging 環境已經 deploy 完成。"))
        assertTrue(output.contains("3. 下週二要上 production。"))
    }

    @Test
    fun testNumberedListEn() {
        val input = "we have three action items today first review the PR second deploy to staging third run benchmark"
        val vocab = listOf("action items", "PR", "deploy", "staging", "benchmark")
        val output = LocalSemanticFormatter.format(input, vocab)

        assertTrue(output.startsWith("We have three action items today:"))
        assertTrue(output.contains("1. Review the PR."))
        assertTrue(output.contains("2. Deploy to staging."))
        assertTrue(output.contains("3. Run benchmark."))
    }

    @Test
    fun testBulletListEn() {
        val input = "here are the key takeaways from the discussion we fixed the memory leak and resolved connection timeout"
        val output = LocalSemanticFormatter.format(input)

        assertTrue(output.startsWith("Here are the key takeaways:"))
        assertTrue(output.contains("- Fixed the memory leak"))
        assertTrue(output.contains("- Resolved connection timeout"))
    }

    @Test
    fun testMultiParagraphZh() {
        val input = "目前這個 issue 我們已經在 staging 驗證過了 基本上沒什麼問題 另外 關於下週一的 sprint planning 我們預計會把重心放在重構 API 模組"
        val vocab = listOf("issue", "staging", "sprint planning", "API")
        val output = LocalSemanticFormatter.format(input, vocab)

        assertTrue(output.contains("\n\n另外，關於下週一的 sprint planning"))
        assertTrue(output.contains("基本上沒什麼問題。"))
    }

    @Test
    fun testMultiParagraphEn() {
        val input = "the backend release is scheduled for tomorrow morning everything looks stable additionally please notify the mobile team once it is deployed"
        val output = LocalSemanticFormatter.format(input)

        assertTrue(output.contains(".\n\nAdditionally, please notify the mobile team once it is deployed."))
    }

    @Test
    fun testChainedFillersRemoval() {
        val input = "那個 呃 就是說 其實我覺得 這個架構方向可以再調整一下"
        val output = LocalSemanticFormatter.format(input)
        assertEquals("其實我覺得這個架構方向可以再調整一下。", output)
    }

    @Test
    fun testChineseHomophoneCorrections() {
        val input = "明天灰達跟連發客要在線上開會 我們再確認晶原代工的產能布署"
        val output = LocalSemanticFormatter.format(input)
        assertTrue(output.contains("輝達"))
        assertTrue(output.contains("聯發科"))
        assertTrue(output.contains("晶圓代工"))
        assertTrue(output.contains("部署"))
    }

    @Test
    fun testChineseCustomVocabularyRestoration() {
        val input = "這週要進行大語言模型的邊緣端推理驗證"
        val vocab = listOf("大語言模型", "邊緣端")
        val output = LocalSemanticFormatter.format(input, vocab)
        assertTrue(output.contains("大語言模型"))
        assertTrue(output.contains("邊緣端"))
    }

    @Test
    fun testDefaultModelIsGemini3FlashWithZeroThinking() {
        assertEquals("gemini-3-flash-preview", SettingsManager.DEFAULT_MODEL)
        assertEquals("gemini-3-flash-preview", GeminiAudioClient.DEFAULT_MODEL)
        assertEquals("gemini-3.1-flash-lite", GeminiAudioClient.FALLBACK_LITE_MODEL)
        assertEquals(0, GeminiAudioClient.THINKING_BUDGET)
    }

    @Test
    fun testLocalAdaptiveEngineHasZeroCloudDependencies() {
        val clazz = LocalAdaptivePolishingEngine::class.java
        val fieldTypes = clazz.declaredFields.map { it.type.name }
        for (typeName in fieldTypes) {
            assertFalse("LocalAdaptivePolishingEngine must NEVER hold GeminiAudioClient", typeName.contains("GeminiAudioClient"))
            assertFalse("LocalAdaptivePolishingEngine must NEVER hold OkHttpClient", typeName.contains("OkHttpClient"))
            assertFalse("LocalAdaptivePolishingEngine must NEVER hold NetworkStateMonitor", typeName.contains("NetworkStateMonitor"))
        }
    }

    @Test
    fun testGeminiRequestPayloadDisablesThinkingAndIncludesFullHomophoneRules() {
        val client = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val largeVocab = (1..60).map { "Term_$it" }
        val jsonStr = client.buildTextRequestJson("那個 呃 請幫我測試一下這段文字，謝謝大家。", largeVocab)
        val root = JSONObject(jsonStr)

        // 1. Verify thinkingBudget is strictly 0
        val genConfig = root.getJSONObject("generationConfig")
        assertTrue("maxOutputTokens must be <= 1024", genConfig.getInt("maxOutputTokens") <= 1024)
        val thinkingConfig = genConfig.getJSONObject("thinkingConfig")
        assertEquals(0, thinkingConfig.getInt("thinkingBudget"))

        // 2. Verify vocabulary injection is strictly capped at 30 terms
        val sysInstruction = root.getJSONObject("system_instruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysInstruction.contains("Term_30"))
        assertFalse("Vocabulary beyond 30 terms must not be injected into prompt", sysInstruction.contains("Term_31"))

        // 3. Verify detailed homophone rules and trailing hallucination instructions exist
        assertTrue("System prompt must instruct active homophone correction", sysInstruction.contains("同音"))
        assertTrue("System prompt must instruct removing trailing 謝謝大家 hallucinations", sysInstruction.contains("謝謝大家"))

        // 4. Verify preCleanRawText stripped trailing "謝謝大家" before sending to Gemini
        val userPartText = root.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertFalse("Trailing 謝謝大家 must be stripped before sending to Gemini", userPartText.contains("謝謝大家"))
    }

    @Test
    fun testChineseSentencesRouteToGeminiForAccuracy() {
        // <= 4 chars trivial affirmations -> Local Fast-Path (true)
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("好"))
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("好的謝謝"))
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("沒問題"))

        // >= 5 chars everyday and long Chinese sentences MUST route to Cloud Gemini (false) for homophone accuracy
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath("那個 呃 明天早上十點我們在線上開會討論專案進度"))
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath("已經把 PR merge 到 main branch 了"))
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath("請幫我確認一下這個問題有沒有修好"))

        // > 100 chars long Chinese utterances -> Cloud Gemini (false)
        val longUtterance = "今天下午我們跟聯發科還有輝達的工程團隊在線上開會討論下一階段晶圓代工與封測的產能配置，第一個重點是目前的架構測試已經順利完成，第二個重點是我們必須在下週二之前再確認一次資安與機房的部署細節，請大家務必準時提交報告。"
        assertTrue(longUtterance.length > 100)
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath(longUtterance))
    }

    @Test
    fun testWhisperTrailingHallucinationStripping() {
        // 1. Short/medium Chinese sentences with hallucinated trailing "謝謝大家" / "謝謝你" / "謝謝觀看"
        assertEquals(
            "明天早上十點我們要開會討論專案進度。",
            LocalSemanticFormatter.format("明天早上十點我們要開會討論專案進度，謝謝大家。")
        )
        assertEquals(
            "請幫我確認一下這個問題有沒有修好。",
            LocalSemanticFormatter.format("請幫我確認一下這個問題有沒有修好 謝謝你")
        )
        assertEquals(
            "這個功能今天已經部署上線了。",
            LocalSemanticFormatter.format("這個功能今天已經部署上線了。謝謝觀看！")
        )
        assertEquals(
            "我們稍後再同步一下測試結果。",
            LocalSemanticFormatter.format("我們稍後再同步一下測試結果，字幕由 Amara.org 社群提供")
        )

        // 2. > 100 chars long utterance with trailing "謝謝大家"
        val longInput = "今天下午我們跟聯發科還有輝達的工程團隊在線上開會討論下一階段晶圓代工與封測的產能配置，目前的架構測試已經順利完成，我們會在下週二之前再確認一次資安與機房的部署細節，謝謝大家。"
        val formattedLong = LocalSemanticFormatter.format(longInput)
        assertFalse("Must strip trailing 謝謝大家 from >100 char speech", formattedLong.contains("謝謝大家"))
        assertTrue(formattedLong.endsWith("部署細節。"))

        // 3. Standalone "謝謝你" or "謝謝大家" (when user genuinely only said thanks) MUST be preserved!
        assertEquals("謝謝你。", LocalSemanticFormatter.format("謝謝你"))
        assertEquals("謝謝大家。", LocalSemanticFormatter.format("謝謝大家"))
    }

    @Test
    fun testNoHttpHeadPrewarmInClients() {
        // Static source-code contract test: Neither GeminiAudioClient nor GroqWhisperClient may contain .head()
        val geminiSrc = File("src/main/java/com/typeless/ime/ai/GeminiAudioClient.kt").readText()
        val groqSrc = File("src/main/java/com/typeless/ime/ai/GroqWhisperClient.kt").readText()
        assertFalse("GeminiAudioClient must NEVER use .head() (returns HTTP 404 on Google API)", geminiSrc.contains(".head()"))
        assertFalse("GroqWhisperClient must NEVER use .head() (returns HTTP 404 on Groq API)", groqSrc.contains(".head()"))
    }

    @Test
    fun testGroqWhisperIncludesLanguageZhAndBalancedSeeds() {
        val groqSrc = File("src/main/java/com/typeless/ime/ai/GroqWhisperClient.kt").readText()
        assertTrue(
            "GroqWhisperClient must explicitly set language=zh for accurate Mandarin recognition",
            groqSrc.contains("\"language\", \"zh\"") || groqSrc.contains("\"language\", DEFAULT_LANGUAGE")
        )
        val zhSeedCount = VocabularyDatabaseHelper.DEFAULT_SEEDS.count { (term, _) ->
            term.any { it.code in 0x4E00..0x9FFF }
        }
        assertTrue("DEFAULT_SEEDS must contain at least 12 Traditional Chinese terms (found $zhSeedCount)", zhSeedCount >= 12)
    }

    @Test
    fun testAudioRequestPayloadDisablesThinkingAndCapsVocab() {
        val client = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val largeVocab = (1..60).map { "AudioTerm_$it" }
        val jsonStr = client.buildAudioRequestJson("ZHVtbXlJbmxpbmVEYXRh", largeVocab)
        val root = JSONObject(jsonStr)

        val genConfig = root.getJSONObject("generationConfig")
        assertTrue("maxOutputTokens must be <= 1024", genConfig.getInt("maxOutputTokens") <= 1024)
        val thinkingConfig = genConfig.getJSONObject("thinkingConfig")
        assertEquals(0, thinkingConfig.getInt("thinkingBudget"))

        val sysInstruction = root.getJSONObject("system_instruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysInstruction.contains("AudioTerm_30"))
        assertFalse(sysInstruction.contains("AudioTerm_31"))
    }

    @Test
    fun testDatabaseRollbackCompatibility() {
        // 1. DATABASE_VERSION must remain 1 so rolling back to main never triggers SQLiteException
        assertEquals(1, VocabularyDatabaseHelper.DATABASE_VERSION)

        // 2. VocabularyDatabaseHelper must explicitly override onDowngrade(SQLiteDatabase, Int, Int)
        val onDowngradeMethod = VocabularyDatabaseHelper::class.java.getDeclaredMethod(
            "onDowngrade",
            SQLiteDatabase::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        assertNotNull("VocabularyDatabaseHelper must override onDowngrade for safe rollback", onDowngradeMethod)
        assertEquals(VocabularyDatabaseHelper::class.java, onDowngradeMethod.declaringClass)
    }

    @Test
    fun testLiveGeminiEndpointContractNeverReturns404() {
        val client = GeminiAudioClient(apiKeyProvider = { "contract_probe_key" })
        val payload = client.buildTextRequestJson("測試連線", emptyList())
        val http = OkHttpClient()

        for (model in listOf(GeminiAudioClient.DEFAULT_MODEL, GeminiAudioClient.FALLBACK_LITE_MODEL)) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", "AIzaSy_CONTRACT_PROBE")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                // A 404 or 405 means the URL path, HTTP verb, or model ID does not exist on Google's server.
                // A 400 (API_KEY_INVALID) or 200 proves the endpoint + HTTP method + model route is 100% valid!
                assertNotEquals("Model endpoint $url returned 404 Not Found!", 404, resp.code)
                assertNotEquals("Model endpoint $url returned 405 Method Not Allowed!", 405, resp.code)
            }
        }
    }
}
