package com.typeless.ime.engine

import org.junit.Assert.assertEquals
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
}
