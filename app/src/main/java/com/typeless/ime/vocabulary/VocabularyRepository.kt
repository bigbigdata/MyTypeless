package com.typeless.ime.vocabulary

import android.content.Context
import android.util.Log
import com.typeless.ime.BuildConfig
import java.text.BreakIterator
import java.util.Locale
import kotlin.concurrent.thread

/**
 * VocabularyRepository
 *
 * Coordinates vocabulary persistence, dynamic prompt bias generation for Whisper,
 * known-term extraction for Gemini, and automatic harvesting (Auto-Discovery)
 * of new technical terminology, proper nouns, and code-switching expressions.
 */
class VocabularyRepository private constructor(context: Context) {

    private val dbHelper = VocabularyDatabaseHelper(context.applicationContext)

    init {
        try {
            dbHelper.purgeAutoHarvestedTerms()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VocabularyRepository"
        private const val DEFAULT_WHISPER_PREFIX = "以下是台灣繁體中文的日常與工作語音逐字稿，請使用繁體中文標點符號，常見詞彙包含："
        private const val MAX_WHISPER_PROMPT_CHARS = 150
        private const val MAX_ENGLISH_TERMS_IN_WHISPER_PROMPT = 10

        /**
         * Common English function words and stop words to exclude from auto-harvesting.
         */
        private val ENGLISH_STOP_WORDS = hashSetOf(
            "the", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with",
            "about", "against", "between", "into", "through", "during", "before",
            "after", "above", "below", "from", "up", "down", "out", "off", "over",
            "under", "again", "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "any", "both", "each", "few", "more",
            "most", "other", "some", "such", "no", "nor", "not", "only", "own",
            "same", "so", "than", "too", "very", "can", "will", "just", "don",
            "should", "now", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "doing", "a",
            "an", "it", "its", "this", "that", "these", "those", "am", "i", "you",
            "he", "she", "we", "they", "me", "him", "her", "us", "them", "my",
            "your", "his", "their", "our", "what", "which", "who", "whom", "ok",
            "yes", "yeah", "sure", "hi", "hello", "good", "well", "fine"
        )

        /**
         * Common Traditional Chinese function words, particles, pronouns, and high-frequency generic terms
         * to exclude from auto-harvesting (only distinct domain terminology, proper nouns, and entity phrases
         * should be harvested).
         */
        private val CHINESE_STOP_WORDS = hashSetOf(
            // 代詞 / 指示詞
            "我們", "你們", "他們", "她們", "自己", "大家", "這個", "那個", "這些", "那些",
            "什麼", "甚麼", "哪裡", "那裡", "這裡", "怎麼", "如何", "這樣", "那樣", "某個",
            // 助詞 / 結構詞 / 贅字 / 處所
            "的話", "等等", "之類", "之後", "之前", "之中", "之間", "以外", "以上", "以下",
            "來說", "而言", "其實", "基本上", "就是", "那個", "這個", "時候", "地方", "東西",
            // 介系詞 / 連接詞
            "因為", "所以", "如果", "雖然", "但是", "不過", "而且", "並且", "或者", "還是",
            "以及", "關於", "對於", "透過", "根據", "按照", "儘管", "另外", "除了", "還有",
            // 常見日常動詞 / 助動詞 / 泛用詞
            "可以", "能夠", "應該", "必須", "需要", "可能", "覺得", "認為", "知道", "看到",
            "聽到", "想要", "進行", "處理", "完成", "開始", "確認", "討論", "提供", "使用",
            "利用", "出現", "發現", "產生", "造成", "影響", "包括", "包含", "表示", "說明",
            "報告", "檢查", "準備", "希望", "決定", "幫忙", "協助", "麻煩",
            // 時間 / 頻率副詞
            "今天", "明天", "昨天", "現在", "目前", "最近", "剛剛", "稍後", "之後", "以前",
            "以後", "隨時", "隨即", "一直", "已經", "正在", "曾經", "將會", "馬上", "立刻",
            // 程度 / 數量詞
            "一個", "一些", "很多", "非常", "十分", "比較", "特別", "稍微", "大概", "幾乎",
            "全部", "所有", "第一", "第二", "第三", "最後", "首先", "其次", "重點", "項目",
            "問題", "情況", "內容", "部分", "階段"
        )

        @Volatile
        private var INSTANCE: VocabularyRepository? = null

        fun getInstance(context: Context): VocabularyRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VocabularyRepository(context).also { INSTANCE = it }
            }
        }
    }

    /**
     * Builds a token-safe, biased prompt string for Groq/OpenAI Whisper API.
     * Picks top-frequency, recently used terms and caps strictly within ~140 chars / < 200 tokens.
     */
    fun buildWhisperPrompt(
        prefix: String = DEFAULT_WHISPER_PREFIX,
        maxChars: Int = MAX_WHISPER_PROMPT_CHARS
    ): String {
        val topTerms = dbHelper.getTopTerms(limit = 50)
        if (topTerms.isEmpty()) {
            return prefix
        }

        // Prioritize Traditional Chinese terms first, and cap pure English terms so Whisper's Mandarin acoustic model stays anchored
        val zhTerms = topTerms.filter { term -> term.any { it.code > 127 } }
        val enTerms = topTerms.filter { term -> term.all { it.code <= 127 } }.take(MAX_ENGLISH_TERMS_IN_WHISPER_PROMPT)
        val balancedTerms = zhTerms + enTerms

        val sb = StringBuilder(prefix)
        var addedCount = 0

        for (term in balancedTerms) {
            val candidate = if (addedCount == 0) term else "、$term"
            if (sb.length + candidate.length + 1 > maxChars) {
                break
            }
            sb.append(candidate)
            addedCount++
        }
        sb.append("。")
        val finalPrompt = sb.toString()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Assembled dynamic Whisper prompt ($addedCount terms, ${finalPrompt.length} chars)")
        }
        return finalPrompt
    }

    /**
     * Retrieves top vocabulary terms for Gemini System Prompt injection.
     * Capped at 30 high-frequency & recently used terms to keep prompt tokens compact.
     */
    fun getAllTermsForGemini(limit: Int = 30): List<String> {
        return dbHelper.getTopTerms(limit = limit)
    }

    /**
     * Asynchronously inspects the final text for existing custom/curated vocabulary hits
     * and increments their usage frequency (without polluting the database with auto-split everyday words).
     */
    fun recordUsageAsync(outputText: String) {
        if (outputText.isBlank()) return

        thread(start = true, name = "VocabUsageTrackerThread") {
            try {
                val allTerms = dbHelper.getAllTerms()
                var matchCount = 0
                for (term in allTerms) {
                    if (term.isBlank()) continue
                    val isMatch = if (term.all { it.isLetter() && it.code < 128 }) {
                        outputText.contains(term, ignoreCase = true)
                    } else {
                        outputText.contains(term)
                    }

                    if (isMatch) {
                        dbHelper.incrementFrequency(term)
                        matchCount++
                    }
                }

                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Usage tracking finished: matched $matchCount existing curated/manual terms"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record vocabulary usage", e)
            }
        }
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    fun getAllItems(searchQuery: String? = null): List<VocabularyItem> {
        return dbHelper.getAllItems(searchQuery)
    }

    fun insertOrIncrement(term: String, tag: String = "General"): Long {
        return dbHelper.insertOrIncrement(term, tag)
    }

    fun deleteItem(id: Long): Boolean {
        return dbHelper.deleteItem(id)
    }

    fun resetToDefaults() {
        dbHelper.resetToDefaults()
    }

    fun getCount(): Int {
        return dbHelper.getCount()
    }
}
