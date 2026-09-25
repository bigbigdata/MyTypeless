package com.typeless.ime.engine

import android.content.Context
import android.util.Log
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
        private const val CLOUD_TIMEOUT_MS = 2000L
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
    private val localAdaptiveEngine = LocalAdaptivePolishingEngine(context)
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
        Log.d(TAG, "Starting process for jobId=$jobId")

        val isOnline = networkMonitor.isOnline
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
                val result = future.get(CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                result.onSuccess { text ->
                    if (text.isNotBlank()) rawTranscript = text
                }.onFailure { err ->
                    Log.w(TAG, "Cloud Groq STT failed: ${err.message}, attempting fallback")
                }
            } catch (e: TimeoutException) {
                Log.w(TAG, "Cloud Groq STT timed out (> ${CLOUD_TIMEOUT_MS}ms), activating circuit breaker")
            } catch (e: Exception) {
                Log.w(TAG, "Cloud Groq STT exception: ${e.message}")
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
                Log.w(TAG, "Local STT failed or unavailable: ${err.message}")
            }
        }

        if (!activeJobCheck()) return Result.failure(Exception("Job cancelled"))

        if (rawTranscript.isNullOrBlank()) {
            return Result.failure(Exception("未能辨識出清晰文字（雲端逾時且本機無可用音訊）"))
        }

        val rawText = rawTranscript!!
        Log.i(TAG, "===> [Stage 1 Output]: \"$rawText\" (Offline: $usedOfflineStt)")

        // ==========================================
        // LATENCY OPTIMIZATION: FAST-PATH BYPASS
        // ==========================================
        if (isFastPathCandidate(rawText)) {
            val fastOutput = ruleEngine.polish(rawText, knownVocabulary).getOrDefault(rawText)
            Log.i(TAG, "===> [Fast-Path Direct]: \"$fastOutput\"")
            return Result.success(
                PipelineOutput(
                    text = fastOutput,
                    statusBadge = "⚡ 極速直出 (Fast-Path)",
                    isOfflineFallback = false
                )
            )
        }

        // ==========================================
        // STAGE 2: TEXT POLISHING & RESTRUCTURING
        // ==========================================
        if (isOnline && settingsManager.hasApiKey && !usedOfflineStt) {
            onStatusUpdate("🤖 Gemini 潤飾中...")
            try {
                val future = executor.submit(Callable {
                    cloudPolishingEngine.polish(rawText, knownVocabulary)
                })
                val polishResult = future.get(CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!activeJobCheck()) return Result.failure(Exception("Job cancelled"))

                if (polishResult.isSuccess) {
                    val polishedText = polishResult.getOrThrow()
                    val finalText = if (polishedText.isNotBlank()) polishedText else rawText
                    return Result.success(
                        PipelineOutput(
                            text = finalText,
                            statusBadge = "⚡ Groq + 🤖 Gemini 潤飾完成",
                            isOfflineFallback = false
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cloud Gemini polish failed or timed out: ${e.message}, falling back to local adaptive engine")
            }
        }

        // Stage 2 Offline / Local Adaptive Polishing
        onStatusUpdate("📴 本地小模型潤飾中...")
        val localPolishResult = localAdaptiveEngine.polish(rawText, knownVocabulary)
        val finalText = localPolishResult.getOrDefault(rawText)

        val badge = if (usedOfflineStt) {
            "📴 離線模式：原生 ASR + ${localAdaptiveEngine.name}"
        } else {
            "📴 降級模式：Groq + ${localAdaptiveEngine.name}"
        }

        return Result.success(
            PipelineOutput(
                text = finalText,
                statusBadge = badge,
                isOfflineFallback = true
            )
        )
    }

    private fun isFastPathCandidate(text: String): Boolean {
        val trimmed = text.trim()
        val fastRegex = Regex("^(好|好的|好啊|可以|沒問題|没问题|收到|謝謝|谢谢|對|对|OK|ok|yes|Yes|好沒問題|好的謝謝|收到謝謝)[，,。！？!~]*$")
        return fastRegex.matches(trimmed)
    }
}
