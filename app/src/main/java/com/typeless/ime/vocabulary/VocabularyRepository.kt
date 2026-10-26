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

    companion object {
        private const val TAG = "VocabularyRepository"
        private const val DEFAULT_WHISPER_PREFIX = "繁體中文日常與工作語音，常見詞："
        private const val MAX_WHISPER_PROMPT_CHARS = 140

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
        val topTerms = dbHelper.getTopTerms(limit = 40)
        if (topTerms.isEmpty()) {
            return prefix
        }

        val sb = StringBuilder(prefix)
        var addedCount = 0

        for (term in topTerms) {
            val candidate = if (addedCount == 0) term else ", $term"
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
     * Supplies up to 150 high-frequency & recently used terms for fuzzy homophone correction.
     */
    fun getAllTermsForGemini(limit: Int = 150): List<String> {
        return dbHelper.getTopTerms(limit = limit)
    }

    /**
     * Asynchronously:
     * 1. Inspects the final text for existing custom vocabulary hits and increments frequency.
     * 2. Auto-Harvests new English terms/acronyms and Traditional Chinese proper nouns/domain entities.
     */
    fun recordUsageAsync(outputText: String) {
        if (outputText.isBlank()) return

        thread(start = true, name = "VocabUsageTrackerThread") {
            try {
                // Step 1: Update existing vocabulary frequencies
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

                // Step 2a: Auto-Harvest English words & technical acronyms
                val wordRegex = Regex("[a-zA-Z0-9#+.-]{2,}")
                val matches = wordRegex.findAll(outputText)
                var newHarvestCount = 0

                for (m in matches) {
                    val rawWord = m.value.trim(',', '.', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']')
                    if (rawWord.length < 2 || rawWord.length > 32) continue
                    // Skip pure digits (e.g. "123", "2026")
                    if (rawWord.all { it.isDigit() }) continue
                    // Skip common English stop/function words
                    if (ENGLISH_STOP_WORDS.contains(rawWord.lowercase())) continue

                    val id = dbHelper.insertOrIncrement(rawWord, tag = "Auto")
                    if (id > 0) {
                        newHarvestCount++
                    }
                }

                // Step 2b: Auto-Harvest Traditional Chinese proper nouns and domain entities
                try {
                    val iterator = BreakIterator.getWordInstance(Locale.TRADITIONAL_CHINESE)
                    iterator.setText(outputText)
                    var start = iterator.first()
                    var end = iterator.next()
                    while (end != BreakIterator.DONE) {
                        val rawWord = outputText.substring(start, end).trim()
                        if (rawWord.length in 2..6 && rawWord.all { it.isCjk() }) {
                            if (!CHINESE_STOP_WORDS.contains(rawWord)) {
                                val id = dbHelper.insertOrIncrement(rawWord, tag = "Auto")
                                if (id > 0) {
                                    newHarvestCount++
                                }
                            }
                        }
                        start = end
                        end = iterator.next()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Chinese word harvesting encounter error", e)
                }

                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Usage tracking finished: matched $matchCount existing, auto-harvested/updated $newHarvestCount terms"
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
