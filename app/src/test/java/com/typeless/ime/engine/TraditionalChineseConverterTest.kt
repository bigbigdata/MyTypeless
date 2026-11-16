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

    @Test
    fun testDoesNotCorruptValidTraditionalChineseWords() {
        // Must NEVER corrupt valid Traditional Chinese words like 干擾->幹擾, 上游->上遊, 台積電->臺積電, 阿里山->阿裡山
        val validTraditional = "請不要干擾或干預這項不相干的台積電上游與下游產能測試，我們在後台觀察十分鐘後再去游泳。"
        assertEquals(validTraditional, TraditionalChineseConverter.toTraditional(validTraditional))
    }

    @Test
    fun testCorrectOneToManySimplifiedToTraditionalPhrases() {
        // Must convert 十分钟->十分鐘 (not 十分鍾), 复杂->複雜 (not 復雜), 复制->複製 (not 復製), 关系->關係 (not 關系), 联系->聯繫
        val simplified = "今天我们花了十分钟复制这份复杂的系统架构图，没关系，稍后再联系里面跟后面的同事确认。"
        val expected = "今天我們花了十分鐘複製這份複雜的系統架構圖，沒關係，稍後再聯繫裡面跟後面的同事確認。"
        assertEquals(expected, TraditionalChineseConverter.toTraditional(simplified))
    }
}
