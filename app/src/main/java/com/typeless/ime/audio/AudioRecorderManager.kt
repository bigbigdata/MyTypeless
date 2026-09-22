package com.typeless.ime.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * AudioRecorderManager
 * 
 * Records high-quality compressed AAC audio (M4A container) using Android hardware encoders:
 * 1. Encodes AAC (16kHz, 32kbps Mono), reducing file size by >85% compared to raw WAV for minimal battery drain and swift network upload.
 * 2. Supports prolonged dictation with a built-in 15-minute safety threshold against accidental pocket recordings.
 * 3. Samples maxAmplitude in real time for the keyboard visualizer progress bar.
 */
class AudioRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 32000
        const val MAX_RECORDING_MS = 15 * 60 * 1000L // 15-minute safety limit
    }

    private val lock = Any()
    private var mediaRecorder: MediaRecorder? = null
    @Volatile
    private var isRecording = false
    private var monitorThread: Thread? = null
    private var outputFile: File? = null
    private var recordingStartTime: Long = 0L

    /**
     * Starts AAC compressed audio recording.
     * @param onAmplitude Real-time normalized amplitude callback (0.0 ~ 1.0).
     * @param onTimeout Callback triggered when the 15-minute safety limit is reached.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        onAmplitude: (Float) -> Unit,
        onTimeout: (() -> Unit)? = null
    ): Boolean = synchronized(lock) {
        if (isRecording) return false

        releaseInternal()

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val file = File(context.cacheDir, "typeless_voice_${System.currentTimeMillis()}.m4a")
            outputFile = file

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder internal error: what=$what, extra=$extra")
                }
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // Start background thread to sample audio amplitude periodically
            monitorThread = thread(start = true, name = "TypelessAudioMonitorThread") {
                while (isRecording) {
                    val amp = try {
                        mediaRecorder?.maxAmplitude ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    val normalized = (amp / 32767.0f).coerceIn(0f, 1f)
                    onAmplitude(normalized)

                    if (System.currentTimeMillis() - recordingStartTime >= MAX_RECORDING_MS) {
                        Log.i(TAG, "Maximum recording duration reached (${MAX_RECORDING_MS / 60000} mins), stopping automatically")
                        isRecording = false
                        onTimeout?.invoke()
                        break
                    }

                    try {
                        Thread.sleep(60)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            releaseInternal()
            return false
        }
    }

    /**
     * Stops recording and returns the completed M4A audio file.
     */
    fun stopRecording(): File? = synchronized(lock) {
        if (!isRecording) return null
        isRecording = false

        try {
            monitorThread?.join(300)
        } catch (_: Exception) {}
        monitorThread = null

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MediaRecorder", e)
        }

        releaseInternal()
        return outputFile
    }

    /**
     * Cancels current recording and cleans up temporary resources (e.g. when dismissing keyboard).
     */
    fun cancelRecording() = synchronized(lock) {
        if (isRecording) {
            isRecording = false
            try {
                monitorThread?.join(200)
            } catch (_: Exception) {}
            monitorThread = null

            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {}
        }

        releaseInternal()
        outputFile?.let { file ->
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
        outputFile = null
    }

    private fun releaseInternal() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MediaRecorder", e)
        }
        mediaRecorder = null
        isRecording = false
    }
}
