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
import com.typeless.ime.ai.GroqWhisperClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Universal Speech-to-Text abstraction supporting both cloud Whisper and on-device native speech recognizers.
 */
interface SpeechToTextEngine {
    val name: String
    val isAvailable: Boolean
    val isOfflineCapable: Boolean

    /**
     * Transcribes speech audio to text.
     */
    fun transcribe(audioFile: File, prompt: String? = null): Result<String>
}

/**
 * Stage 1 Cloud STT: High-accuracy Groq Whisper (whisper-large-v3-turbo).
 */
class GroqWhisperSttEngine(
    private val groqClient: GroqWhisperClient,
    private val isKeyConfigured: () -> Boolean
) : SpeechToTextEngine {

    override val name: String = "Groq Whisper (Cloud)"
    override val isAvailable: Boolean get() = isKeyConfigured()
    override val isOfflineCapable: Boolean = false

    override fun transcribe(audioFile: File, prompt: String?): Result<String> {
        return groqClient.transcribe(audioFile, prompt)
    }
}

/**
 * Stage 1 On-Device STT: Android Native SpeechRecognizer (Offline fallback).
 * Uses SpeechRecognizer.createOnDeviceSpeechRecognizer on API 31+ for zero-data/zero-latency transcription.
 */
class AndroidOnDeviceSttEngine(
    private val context: Context
) : SpeechToTextEngine {

    companion object {
        private const val TAG = "AndroidOnDeviceStt"
        private const val TIMEOUT_SECONDS = 5L
    }

    override val name: String = "Android On-Device ASR (Offline)"
    override val isOfflineCapable: Boolean = true

    override val isAvailable: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else {
                SpeechRecognizer.isRecognitionAvailable(context)
            }
        }

    /**
     * Transcribes speech. When called with a pre-recorded file, attempts system fallback or reports status.
     */
    override fun transcribe(audioFile: File, prompt: String?): Result<String> {
        if (!isAvailable) {
            return Result.failure(IllegalStateException("系統不支援端側離線語音辨識"))
        }

        // Android SpeechRecognizer operates on the main thread
        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Result<String>>()

        mainHandler.post {
            try {
                val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.w(TAG, "On-device SpeechRecognizer error code: $error")
                        resultRef.set(Result.failure(Exception("端側語音辨識錯誤: $error")))
                        recognizer.destroy()
                        latch.countDown()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotBlank()) {
                            resultRef.set(Result.success(text))
                        } else {
                            resultRef.set(Result.failure(Exception("未能辨識出有效文字")))
                        }
                        recognizer.destroy()
                        latch.countDown()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                // Prepares or triggers recognition with offline intent
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start on-device SpeechRecognizer: ${e.message}")
                resultRef.set(Result.failure(e))
                latch.countDown()
            }
        }

        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            return Result.failure(Exception("端側語音辨識逾時"))
        }

        return resultRef.get() ?: Result.failure(Exception("端側辨識未返回結果"))
    }
}
