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
 * 負責透過 Android 系統硬體晶片錄製高品質 AAC 壓縮音訊（M4A 格式）：
 * 1. 採用硬體編碼 AAC (16kHz, 32kbps Mono)，體積較 WAV 縮小 85% 以上，極省電且弱網秒傳。
 * 2. 解除原本短時限制，支援長時口述（內建 15 分鐘防誤觸防禦上限）。
 * 3. 實時擷取 maxAmplitude 音量震幅供鍵盤動態進度條顯示。
 */
class AudioRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 32000
        const val MAX_RECORDING_MS = 15 * 60 * 1000L // 15 分鐘防口袋誤觸保護上限
    }

    private val lock = Any()
    private var mediaRecorder: MediaRecorder? = null
    @Volatile
    private var isRecording = false
    private var monitorThread: Thread? = null
    private var outputFile: File? = null
    private var recordingStartTime: Long = 0L

    /**
     * 啟動 AAC 壓縮錄音
     * @param onAmplitude 即時音量回呼（0.0 ~ 1.0）
     * @param onTimeout 達到 15 分鐘安全上限時的回呼
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
                    Log.e(TAG, "MediaRecorder 內部錯誤: what=$what, extra=$extra")
                }
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // 啟動背景執行緒定期取樣音量震幅
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
                        Log.i(TAG, "已達最大錄音保護上限 (${MAX_RECORDING_MS / 60000} 分鐘)，自動停止")
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
            Log.e(TAG, "啟動 MediaRecorder 異常", e)
            releaseInternal()
            return false
        }
    }

    /**
     * 停止錄音並返回錄製完成的 M4A 檔案
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
            Log.e(TAG, "停止 MediaRecorder 失敗", e)
        }

        releaseInternal()
        return outputFile
    }

    /**
     * 放棄當前錄音（切換 App 或鍵盤收起時強制釋放）
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
            Log.e(TAG, "釋放 MediaRecorder 失敗", e)
        }
        mediaRecorder = null
        isRecording = false
    }
}
