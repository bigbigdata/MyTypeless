package com.typeless.ime.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * PixelStreamingSttManager
 * 
 * Manages live-streaming on-device Speech-to-Text recognition using Android's native
 * SpeechRecognizer (createOnDeviceSpeechRecognizer on API 31+ / Pixel devices).
 * 
 * Boundary Specifications:
 * - Input: Real-time microphone audio captured while user holds the record key.
 * - Callbacks:
 *   - onRmsChanged: Audio volume changes for UI progress bar animation.
 *   - onPartialResult: Instant interim transcription updates for user preview.
 *   - onFinalResult: Complete final raw transcript + precise ASR latency in milliseconds.
 *   - onError: Human-readable error message.
 * - Thread Safety: SpeechRecognizer strictly instantiated and invoked on the Main Looper.
 */
class PixelStreamingSttManager(private val context: Context) {

    companion object {
        private const val TAG = "PixelStreamingStt"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var startTimeMs = 0L

    val isAvailable: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
                        SpeechRecognizer.isRecognitionAvailable(context)
            } else {
                SpeechRecognizer.isRecognitionAvailable(context)
            }
        }

    /**
     * Starts live audio streaming recognition.
     */
    fun startListening(
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (transcript: String, asrDurationMs: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ): Boolean {
        if (!isAvailable) {
            onError("此裝置尚未就緒系統離線語音辨識（請確認 Google 語音服務已下載中文套件）")
            return false
        }

        stopAndDestroyRecognizer()

        try {
            startTimeMs = System.currentTimeMillis()

            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.d(TAG, "Creating dedicated On-Device SpeechRecognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.d(TAG, "Creating standard system SpeechRecognizer with offline flag")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer is ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech beginning detected")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech end detected")
                }

                override fun onError(error: Int) {
                    isListening = false
                    val errorMsg = mapErrorCodeToMessage(error)
                    Log.w(TAG, "SpeechRecognizer error: $error ($errorMsg)")
                    onError(errorMsg)
                    stopAndDestroyRecognizer()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    Log.i(TAG, "SpeechRecognizer onResults: \"$text\" in ${elapsed}ms")

                    if (text.isNotBlank()) {
                        onFinalResult(text, elapsed)
                    } else {
                        onError("未偵測到清晰文字")
                    }
                    stopAndDestroyRecognizer()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull()?.trim().orEmpty()
                    if (partialText.isNotBlank()) {
                        onPartialResult(partialText)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
            isListening = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            isListening = false
            onError("啟動原生語音辨識失敗: ${e.message}")
            stopAndDestroyRecognizer()
            return false
        }
    }

    /**
     * Stops listening and requests final speech recognition result.
     */
    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
            }
        }
    }

    /**
     * Cancels active recognition immediately and discards audio.
     */
    fun cancel() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        stopAndDestroyRecognizer()
    }

    private fun stopAndDestroyRecognizer() {
        isListening = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun mapErrorCodeToMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音訊錄製錯誤"
            SpeechRecognizer.ERROR_CLIENT -> "客戶端系統錯誤"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麥克風權限"
            SpeechRecognizer.ERROR_NETWORK -> "離線語言包未就緒或網路錯誤"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "連線逾時"
            SpeechRecognizer.ERROR_NO_MATCH -> "未能辨識說話內容"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識引擎忙碌中"
            SpeechRecognizer.ERROR_SERVER -> "語音辨識服務異常"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未偵測到聲音"
            else -> "語音辨識錯誤 ($error)"
        }
    }
}
