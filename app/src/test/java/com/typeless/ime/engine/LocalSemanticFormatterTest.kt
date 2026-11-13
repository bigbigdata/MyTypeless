package com.typeless.ime.engine

import android.database.sqlite.SQLiteDatabase
import com.typeless.ime.SettingsManager
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.vocabulary.VocabularyDatabaseHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testDefaultModelIsFlashLite() {
        assertEquals("gemini-2.5-flash-lite", SettingsManager.DEFAULT_MODEL)
        assertEquals("gemini-2.5-flash-lite", GeminiAudioClient.DEFAULT_MODEL)
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
    fun testGeminiRequestPayloadDisablesThinkingAndCapsVocab() {
        val client = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val largeVocab = (1..60).map { "Term_$it" }
        val jsonStr = client.buildTextRequestJson("那個 呃 請幫我測試一下這段文字", largeVocab)
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
    }

    @Test
    fun testOptionBFastPath25CharsRouting() {
        // <= 25 chars everyday speech (even with leading fillers) -> Local Fast-Path (true)
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("好的謝謝"))
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("那個 呃 明天早上十點我們在線上開會討論專案進度"))
        assertTrue(HybridPipelineCoordinator.shouldUseLocalFastPath("已經把 PR merge 到 main branch 了"))

        // Structured list or transition keywords even if short -> Cloud Gemini (false)
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath("今天有兩個重點 第一個是部署 第二個是測試"))
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath("沒問題 另外 明天早上記得開會"))

        // Long utterances (> 25 chars after stripping fillers) -> Cloud Gemini (false)
        val longUtterance = "我們今天下午跟輝達還有聯發科的工程團隊討論了下一階段晶圓代工的產能配置與測試排程"
        assertTrue(longUtterance.length > 25)
        assertFalse(HybridPipelineCoordinator.shouldUseLocalFastPath(longUtterance))
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
}
