package com.typeless.ime.engine

import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.ai.GroqWhisperClient
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAndCascadeReliabilityTest {

    @Test
    fun testNetworkStateMonitorLiveRefreshNeverStaysStuckOffline() {
        val simulatedOnline = AtomicBoolean(false)
        val monitor = NetworkStateMonitor(context = null, liveChecker = { simulatedOnline.get() })

        val observedStates = mutableListOf<Boolean>()
        monitor.addListener { observedStates.add(it) }

        // Initial state is offline
        assertEquals(listOf(false), observedStates)
        assertFalse(monitor.isOnline)

        // Device regains connectivity without delivering a system NetworkCallback
        simulatedOnline.set(true)

        // Reading isOnline must live-probe and immediately recover to true + notify listeners
        assertTrue(monitor.isOnline)
        assertEquals(listOf(false, true), observedStates)

        // Repeated reads while unchanged must not fire duplicate notifications
        assertTrue(monitor.isOnline)
        assertEquals(listOf(false, true), observedStates)

        // Dropping back offline and recovering again is detected immediately
        simulatedOnline.set(false)
        assertFalse(monitor.refreshConnectivity())
        simulatedOnline.set(true)
        assertTrue(monitor.isOnline)
        assertEquals(listOf(false, true, false, true), observedStates)
    }

    @Test
    fun testGemini503ImmediatelyCascadesToNextModelWithTransientCooldown() {
        val client = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val attemptedModels = mutableListOf<String>()

        client.apiCallerOverride = { model, _, _, _ ->
            attemptedModels.add(model)
            if (model == GeminiAudioClient.DEFAULT_MODEL) {
                throw GeminiAudioClient.ServerUnavailableException("HTTP 503: Service Unavailable")
            }
            "潤飾後結果"
        }

        val result = client.polishText("請幫我測試一下這個句子")
        assertTrue(result.isSuccess)
        assertEquals("潤飾後結果", result.getOrNull())
        assertEquals(
            listOf(GeminiAudioClient.DEFAULT_MODEL, GeminiAudioClient.TIER_2_MODEL),
            attemptedModels
        )
        assertEquals(GeminiAudioClient.TIER_2_MODEL, client.lastUsedModel)
        assertTrue(client.isModelInCooldown(GeminiAudioClient.DEFAULT_MODEL))
        assertFalse(client.isModelInCooldown(GeminiAudioClient.TIER_2_MODEL))
        assertFalse(client.isInRateLimitCooldown)

        // Next call skips cooled-down Tier 1 in 0ms and hits Tier 2 directly
        attemptedModels.clear()
        val secondResult = client.polishText("再測一次這段文字")
        assertTrue(secondResult.isSuccess)
        assertEquals(listOf(GeminiAudioClient.TIER_2_MODEL), attemptedModels)
    }

    @Test
    fun testGeminiTimeoutCascadesAndDoesNotEscalateToDailyCooldown() {
        val client = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val attemptedModels = mutableListOf<String>()

        client.apiCallerOverride = { model, _, _, _ ->
            attemptedModels.add(model)
            when (model) {
                GeminiAudioClient.DEFAULT_MODEL -> throw SocketTimeoutException("Read timed out")
                GeminiAudioClient.TIER_2_MODEL -> throw InterruptedIOException("timeout")
                else -> "第三層成功"
            }
        }

        val result = client.polishText("測試單一模型逾時自動切換")
        assertTrue(result.isSuccess)
        assertEquals("第三層成功", result.getOrNull())
        assertEquals(GeminiAudioClient.MODEL_CASCADE, attemptedModels)
        assertEquals(GeminiAudioClient.FALLBACK_LITE_MODEL, client.lastUsedModel)
        assertTrue(client.isModelInCooldown(GeminiAudioClient.DEFAULT_MODEL))
        assertTrue(client.isModelInCooldown(GeminiAudioClient.TIER_2_MODEL))
        assertFalse(client.isModelInCooldown(GeminiAudioClient.FALLBACK_LITE_MODEL))

        // Transient failure cooldown stays at 60s even when triggered multiple times (never 2-hour RPD)
        assertEquals(
            GeminiAudioClient.TRANSIENT_COOLDOWN_MS,
            client.recordModelTransientFailure(GeminiAudioClient.DEFAULT_MODEL)
        )
        assertEquals(
            GeminiAudioClient.TRANSIENT_COOLDOWN_MS,
            client.recordModelTransientFailure(GeminiAudioClient.DEFAULT_MODEL)
        )
    }

    @Test
    fun testClearCooldownsAndEvictIdleConnections() {
        val geminiClient = GeminiAudioClient(apiKeyProvider = { "test_key" })
        val groqClient = GroqWhisperClient(apiKeyProvider = { "test_key" })

        for (model in GeminiAudioClient.MODEL_CASCADE) {
            geminiClient.recordModelRateLimit(model)
        }
        assertTrue(geminiClient.isInRateLimitCooldown)

        geminiClient.clearCooldowns()
        assertFalse(geminiClient.isInRateLimitCooldown)
        for (model in GeminiAudioClient.MODEL_CASCADE) {
            assertFalse(geminiClient.isModelInCooldown(model))
        }

        // Must execute cleanly with zero errors
        geminiClient.evictIdleConnections()
        groqClient.evictIdleConnections()
    }

    @Test
    fun testPipelineAndImeReliabilityContracts() {
        val imeSrc = File("src/main/java/com/typeless/ime/TypelessInputMethodService.kt").readText()
        val coordinatorSrc = File("src/main/java/com/typeless/ime/engine/HybridPipelineCoordinator.kt").readText()

        assertTrue(
            "TypelessInputMethodService must lock recording mode per session",
            imeSrc.contains("isCurrentRecordingOnDevice")
        )
        assertTrue(
            "TypelessInputMethodService must refresh connectivity when keyboard opens",
            imeSrc.contains("networkMonitor.refreshConnectivity()")
        )
        assertTrue(
            "HybridPipelineCoordinator must cancel timed-out futures",
            coordinatorSrc.contains("future.cancel(true)")
        )
    }
}
