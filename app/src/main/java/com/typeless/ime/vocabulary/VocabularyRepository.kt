package com.typeless.ime.vocabulary

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * VocabularyRepository
 *
 * Coordinates vocabulary persistence, dynamic prompt bias generation for Whisper,
 * known-term extraction for Gemini, and automatic harvesting (Auto-Discovery)
 * of new technical terminology and code-switching expressions.
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
        Log.d(TAG, "Assembled dynamic Whisper prompt ($addedCount terms, ${finalPrompt.length} chars): $finalPrompt")
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
     * 2. Auto-Harvests new English terms/acronyms/code-switching keywords and persists them with tag "Auto".
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

                // Step 2: Auto-Harvest new words (code-switching expressions, acronyms, technical terms)
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

                Log.d(
                    TAG,
                    "Usage tracking finished: matched $matchCount existing, auto-harvested/updated $newHarvestCount terms"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record vocabulary usage", e)
            }
        }
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
