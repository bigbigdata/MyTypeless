package com.typeless.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TraditionalChineseConverterTest {

    @Test
    fun testConvertsSimplifiedToTraditional() {
        val input = "关于中文输出的部分可以请你输出繁体中文吗？local端竟然输出简体中文...我不允许了：只想要繁体中文。然后呢我发现local端，我大概十几秒没有讲话，他就会出现语音辨识错误11这个讯息。"
        val expected = "關於中文輸出的部分可以請你輸出繁體中文嗎？local端竟然輸出簡體中文...我不允許了：只想要繁體中文。然後呢我發現local端，我大概十幾秒沒有講話，他就會出現語音辨識錯誤11這個訊息。"
        val actual = TraditionalChineseConverter.toTraditional(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testPreservesEnglishAndNumbers() {
        val input = "今天在 staging 环境 deploy 了 PR #123，meeting 约在 3pm。"
        val expected = "今天在 staging 環境 deploy 了 PR #123，meeting 約在 3pm。"
        val actual = TraditionalChineseConverter.toTraditional(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testCommonTechTerms() {
        val input = "测试网络连接与服务器数据库设置"
        val expected = "測試網路連接與伺服器資料庫設置"
        val actual = TraditionalChineseConverter.toTraditional(input)
        assertEquals(expected, actual)
    }
}
