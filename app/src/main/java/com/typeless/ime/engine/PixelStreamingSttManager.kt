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
 * Manages live audio streaming Speech-to-Text recognition using Android's SpeechRecognizer.
 * 
 * Key Features for Tap-to-Toggle Mode:
 * 1. Multi-segment Speech Accumulation: When user pauses, system VAD does NOT cut off the input.
 *    Segments are accumulated seamlessly until the user explicitly taps Stop.
 * 2. Bilingual Code-Switching: Configured with multilingual intents (zh-TW + en-US).
 * 3. Graceful Auto-Recovery: Automatically falls back if offline voice packs are missing.
 */
class PixelStreamingSttManager(private val context: Context) {

    companion object {
        private const val TAG = "PixelStreamingStt"
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var isUserRecording = false
    private var startTimeMs = 0L
    private var hasRetriedWithStandard = false

    private val accumulatedTranscript = StringBuilder()

    // Callbacks held during active session
    private var cbRmsChanged: ((Float) -> Unit)? = null
    private var cbPartialResult: ((String) -> Unit)? = null
    private var cbFinalResult: ((String, Long) -> Unit)? = null
    private var cbError: ((String) -> Unit)? = null

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
     * Starts listening for user speech in Tap-to-Toggle mode.
     * Keeps accumulating speech until stopListeningByUser() is called.
     */
    fun startListening(
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (transcript: String, asrDurationMs: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ): Boolean {
        hasRetriedWithStandard = false
        isUserRecording = true
        startTimeMs = System.currentTimeMillis()
        accumulatedTranscript.clear()

        cbRmsChanged = onRmsChanged
        cbPartialResult = onPartialResult
        cbFinalResult = onFinalResult
        cbError = onError

        return startListeningInternal(preferOffline = true)
    }

    private fun startListeningInternal(preferOffline: Boolean): Boolean {
        if (!isAvailable) {
            cbError?.invoke("此裝置系統語音服務尚未就緒")
            return false
        }

        stopAndDestroyRecognizer()

        try {
            val recognizer = if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.d(TAG, "Creating dedicated On-Device SpeechRecognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.d(TAG, "Creating standard system SpeechRecognizer (preferOffline=$preferOffline)")
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
                putExtra("android.speech.extra.LANGUAGE_TAG", "cmn-Hant-TW")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "zh-TW", "cmn-Hant-TW", "zh-HK"))
                // Allow multilingual code-switching
                putExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED", true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Extend silence tolerance
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                if (preferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "SpeechRecognizer ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech beginning detected")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    cbRmsChanged?.invoke(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech segment end detected by VAD")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer onError: $error (isUserRecording=$isUserRecording, preferOffline=$preferOffline)")

                    // Auto-recovery for Error 13 (Language unavailable) or Error 12
                    if ((error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) && !hasRetriedWithStandard) {
                        Log.i(TAG, "Offline pack for zh-TW missing (error $error), auto-recovering with system speech service...")
                        hasRetriedWithStandard = true
                        mainHandler.postDelayed({
                            if (isUserRecording) {
                                startListeningInternal(preferOffline = false)
                            }
                        }, 200L)
                        return
                    }

                    if (isUserRecording) {
                        // While the user is still recording in Tap-to-Toggle mode, do NOT abort and do NOT deliver!
                        // Transient errors (such as pause timeouts 6 & 7, server disconnect 11, recognizer busy 8, or client reset 5)
                        // are handled by seamlessly re-arming the recognizer.
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            isUserRecording = false
                            cbError?.invoke("缺少麥克風權限")
                            stopAndDestroyRecognizer()
                            return
                        }

                        Log.d(TAG, "Transient pause/reset (error $error) while user is still recording. Re-arming listener...")
                        stopAndDestroyRecognizer()
                        val delayMs = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 350L
                            SpeechRecognizer.ERROR_CLIENT -> 250L
                            else -> 150L
                        }
                        mainHandler.postDelayed({
                            if (isUserRecording) {
                                startListeningInternal(preferOffline = preferOffline)
                            }
                        }, delayMs)
                        return
                    }

                    // ONLY when user is NO LONGER recording (!isUserRecording):
                    if (accumulatedTranscript.isNotEmpty()) {
                        Log.i(TAG, "User finished recording. Delivering accumulated transcript: $accumulatedTranscript")
                        deliverFinalResult()
                        return
                    }

                    val errorMsg = mapErrorCodeToMessage(error)
                    cbError?.invoke(errorMsg)
                    stopAndDestroyRecognizer()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val rawText = matches?.firstOrNull()?.trim().orEmpty()
                    val text = TraditionalChineseConverter.toTraditional(rawText)
                    Log.i(TAG, "SpeechRecognizer segment onResults: \"$text\" (raw: \"$rawText\", isUserRecording=$isUserRecording)")

                    if (text.isNotBlank()) {
                        if (accumulatedTranscript.isNotEmpty() && !accumulatedTranscript.endsWith(" ")) {
                            accumulatedTranscript.append(" ")
                        }
                        accumulatedTranscript.append(text)
                        // Keep live preview updated with the full accumulated transcript
                        cbPartialResult?.invoke(accumulatedTranscript.toString())
                    }

                    if (isUserRecording) {
                        // User is STILL recording! Continue listening for the next phrase
                        stopAndDestroyRecognizer()
                        mainHandler.postDelayed({
                            if (isUserRecording) {
                                startListeningInternal(preferOffline = preferOffline)
                            }
                        }, 200L)
                    } else {
                        // User has explicitly tapped stop! Deliver full result
                        deliverFinalResult()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val rawPartial = matches?.firstOrNull()?.trim().orEmpty()
                    val partialText = TraditionalChineseConverter.toTraditional(rawPartial)
                    if (partialText.isNotBlank()) {
                        val fullPreview = if (accumulatedTranscript.isNotEmpty()) {
                            "$accumulatedTranscript $partialText"
                        } else {
                            partialText
                        }
                        cbPartialResult?.invoke(fullPreview)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            isUserRecording = false
            cbError?.invoke("啟動語音辨識失敗: ${e.message}")
            stopAndDestroyRecognizer()
            return false
        }
    }

    /**
     * User explicitly taps the Stop button to finish recording.
     */
    fun stopListeningByUser() {
        Log.i(TAG, "User explicitly tapped stop. Finalizing accumulated transcript...")
        isUserRecording = false
        if (speechRecognizer != null) {
            try {
                speechRecognizer?.stopListening()
                mainHandler.postDelayed({
                    if (speechRecognizer != null) {
                        Log.i(TAG, "Stop timeout reached, finalizing immediately.")
                        deliverFinalResult()
                    }
                }, 1000L)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
                deliverFinalResult()
            }
        } else {
            deliverFinalResult()
        }
    }

    private fun deliverFinalResult() {
        isUserRecording = false
        val finalTranscript = accumulatedTranscript.toString().trim()
        val elapsed = System.currentTimeMillis() - startTimeMs
        Log.i(TAG, "Delivering final accumulated transcript: \"$finalTranscript\" in ${elapsed}ms")

        if (finalTranscript.isNotBlank()) {
            cbFinalResult?.invoke(finalTranscript, elapsed)
        } else {
            cbError?.invoke("未偵測到清晰說話內容")
        }
        stopAndDestroyRecognizer()
    }

    /**
     * Cancels active recognition immediately and discards audio.
     */
    fun cancel() {
        isUserRecording = false
        accumulatedTranscript.clear()
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        stopAndDestroyRecognizer()
    }

    private fun stopAndDestroyRecognizer() {
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
            ERROR_SERVER_DISCONNECTED -> "語音辨識連線逾時（請重試）"
            ERROR_LANGUAGE_UNAVAILABLE -> "手機尚未下載繁體中文離線語音包（可至手機「設定 ➔ 系統 ➔ 語言 ➔ 語音輸入」下載繁體中文）"
            ERROR_LANGUAGE_NOT_SUPPORTED -> "此裝置系統未支援該語系離線辨識"
            else -> "語音辨識錯誤 ($error)"
        }
    }
}
