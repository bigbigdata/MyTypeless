package com.typeless.ime.engine

import android.content.Context
import android.util.Log
import com.typeless.ime.BuildConfig
import com.typeless.ime.SettingsManager
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.ai.GroqWhisperClient
import com.typeless.ime.vocabulary.VocabularyRepository
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Result data returned by the HybridPipelineCoordinator.
 */
data class PipelineOutput(
    val text: String,
    val statusBadge: String,
    val isOfflineFallback: Boolean
)

/**
 * HybridPipelineCoordinator:
 * Orchestrates Stage 1 (STT) and Stage 2 (Text Polishing) across cloud and on-device engines.
 * Implements:
 * 1. Automatic network-aware routing (Online vs Offline).
 * 2. 2000ms Cloud Circuit Breaker / Timeout fallback.
 * 3. Fast-Path bypass for common short affirmations.
 * 4. Transparent status badging for user visibility.
 */
class HybridPipelineCoordinator(
    private val context: Context,
    private val networkMonitor: NetworkStateMonitor,
    private val settingsManager: SettingsManager,
    private val vocabularyRepository: VocabularyRepository,
    private val groqClient: GroqWhisperClient,
    private val geminiClient: GeminiAudioClient
) {

    companion object {
        private const val TAG = "HybridCoordinator"
        private const val CLOUD_STT_TIMEOUT_MS = 45_000L  // 45s for audio upload & Groq Whisper transcription of long audio
        private const val CLOUD_POLISH_TIMEOUT_MS = 15_000L // 15s for Gemini text polishing
        const val FAST_PATH_MAX_CHARS = 4

        private val fastAffirmationRegex = Regex(
            "^(好|好的|好啊|可以|沒問題|没问题|收到|謝謝|谢谢|謝謝你|谢谢你|謝謝大家|谢谢大家|對|对|OK|ok|yes|Yes|好沒問題|好的謝謝|收到謝謝)[，,。！？!~]*$"
        )

        /**
         * Counts semantic spoken units across bilingual text:
         * - 1 CJK character = 1 unit
         * - 1 contiguous English/alphanumeric word = 1 unit
         */
        internal fun countSpokenUnits(text: String): Int {
            val cjkCount = text.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
            val englishWords = Regex("[a-zA-Z0-9#+.-]+").findAll(text).count()
            return cjkCount + englishWords
        }

        /**
         * Fast-Path Routing:
         * Only trivial short affirmations (<= 4 chars matching fastAffirmationRegex) bypass Cloud Gemini.
         * All real sentences (> 4 chars) route to Gemini 2.5 Flash (thinkingBudget=0) for high-accuracy
         * Traditional Chinese contextual homophone correction.
         */
        fun shouldUseLocalFastPath(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return true
            return fastAffirmationRegex.matches(trimmed)
        }
    }

    private val executor = Executors.newCachedThreadPool()

    // Engines
    private val cloudSttEngine = GroqWhisperSttEngine(groqClient) { settingsManager.hasGroqApiKey }
    private val localSttEngine = AndroidOnDeviceSttEngine(context)

    private val cloudPolishingEngine = GeminiCloudPolishingEngine(
        geminiClient,
        { settingsManager.model },
        { settingsManager.hasApiKey }
    )
    private val localAdaptiveEngine = LocalAdaptivePolishingEngine(
        context = context
    )
    private val ruleEngine = RuleBasedPolishingEngine()

    /**
     * Executes the complete transcription and polishing pipeline with cloud-to-device fallback.
     */
    fun process(
        audioFile: File,
        jobId: Long,
        activeJobCheck: () -> Boolean,
        onStatusUpdate: (String) -> Unit
    ): Result<PipelineOutput> {
        if (!activeJobCheck()) return Result.failure(Exception("Job cancelled"))
        if (BuildConfig.DEBUG) Log.d(TAG, "Starting process for jobId=$jobId")

        val isOnline = networkMonitor.isOnline && !settingsManager.isForcedOfflineMode
        val hasCloudKeys = settingsManager.hasGroqApiKey || settingsManager.hasApiKey

        val dynamicWhisperPrompt = vocabularyRepository.buildWhisperPrompt()
        val knownVocabulary = vocabularyRepository.getAllTermsForGemini()

        var rawTranscript: String? = null
        var usedOfflineStt = false

        // ==========================================
        // STAGE 1: SPEECH TO TEXT
        // ==========================================
        if (isOnline && hasCloudKeys) {
            onStatusUpdate("⚡ Groq 轉錄中...")
            try {
                // Execute cloud STT with timeout protection
                val future = executor.submit(Callable {
                    cloudSttEngine.transcribe(audioFile, dynamicWhisperPrompt)
                })
                val result = future.get(CLOUD_STT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                result.onSuccess { text ->
                    if (text.isNotBlank()) rawTranscript = text
                }.onFailure { err ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "Cloud Groq STT failed: ${err.message}, attempting fallback")
                }
            } catch (e: TimeoutException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cloud Groq STT timed out (> ${CLOUD_STT_TIMEOUT_MS}ms), activating circuit breaker")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cloud Groq STT exception: ${e.message}")
            }
        }

        // Offline Fallback for Stage 1 if cloud failed or device is offline
        if (rawTranscript.isNullOrBlank()) {
            usedOfflineStt = true
            onStatusUpdate("📴 切換本機端側語音辨識...")
            val localResult = localSttEngine.transcribe(audioFile, dynamicWhisperPrompt)
            localResult.onSuccess { text ->
                if (text.isNotBlank()) rawTranscript = text
            }.onFailure { err ->
                if (BuildConfig.DEBUG) Log.w(TAG, "Local STT failed or unavailable: ${err.message}")
            }
        }

        if (!activeJobCheck()) return Result.failure(Exception("Job cancelled"))

        val rawText = WhisperHallucinationFilter.stripTrailingHallucinations(rawTranscript ?: "")
        if (rawText.isBlank()) {
            return Result.failure(Exception("未能辨識出清晰文字（雲端逾時且本機無可用音訊）"))
        }

        Log.i(TAG, "===> [Stage 1 Output]: ${rawText.length} chars (Offline: $usedOfflineStt)")

        // ==========================================
        // LATENCY & COST OPTIMIZATION: FAST-PATH BYPASS (<= 25 chars)
        // ==========================================
        if (isFastPathCandidate(rawText)) {
            val fastOutput = ruleEngine.polish(rawText, knownVocabulary).getOrDefault(rawText)
            Log.i(TAG, "===> [Fast-Path Direct]: ${fastOutput.length} chars (0 cloud tokens)")
            val fastBadge = if (usedOfflineStt) {
                "📱 純端側完成 (0 雲端 tok)"
            } else {
                "⚡ Groq + 📱 本地極速直出 (0 Gemini tok)"
            }
            return Result.success(
                PipelineOutput(
                    text = fastOutput,
                    statusBadge = fastBadge,
                    isOfflineFallback = usedOfflineStt
                )
            )
        }

        // ==========================================
        // STAGE 2: TEXT POLISHING & RESTRUCTURING (> 25 chars or structured lists/paragraphs)
        // ==========================================
        if (isOnline && settingsManager.hasApiKey && !usedOfflineStt && !geminiClient.isInRateLimitCooldown) {
            onStatusUpdate("🤖 Gemini 長篇潤飾中...")
            try {
                val future = executor.submit(Callable {
                    cloudPolishingEngine.polish(rawText, knownVocabulary)
                })
                val polishResult = future.get(CLOUD_POLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!activeJobCheck()) return Result.failure(Exception("Job cancelled"))

                if (polishResult.isSuccess) {
                    val polishedText = polishResult.getOrThrow()
                    val finalText = if (polishedText.isNotBlank()) polishedText else rawText
                    val callsToday = settingsManager.dailyGeminiCalls
                    val lastToks = settingsManager.lastCallTokens
                    return Result.success(
                        PipelineOutput(
                            text = finalText,
                            statusBadge = "⚡ Groq + 🤖 Gemini (今日第 ${callsToday} 次 | ${lastToks} tok)",
                            isOfflineFallback = false
                        )
                    )
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Cloud Gemini polish failed or timed out: ${e.message}, falling back to local adaptive engine")
            }
        }

        // Stage 2 Offline / Local Adaptive Polishing (strictly 0 cloud tokens)
        onStatusUpdate("📴 本地排版潤飾中...")
        val localPolishResult = localAdaptiveEngine.polish(rawText, knownVocabulary)
        val finalText = localPolishResult.getOrDefault(rawText)

        val badge = when {
            usedOfflineStt -> "📱 純端側完成 (0 雲端 tok)"
            geminiClient.isInRateLimitCooldown -> "📴 Gemini 免費額度冷卻中 ➔ 本地排版保底 (0 tok)"
            else -> "📴 降級模式：Groq + 本地排版 (0 Gemini tok)"
        }

        return Result.success(
            PipelineOutput(
                text = finalText,
                statusBadge = badge,
                isOfflineFallback = true
            )
        )
    }

    internal fun isFastPathCandidate(text: String): Boolean {
        return shouldUseLocalFastPath(text)
    }
}
