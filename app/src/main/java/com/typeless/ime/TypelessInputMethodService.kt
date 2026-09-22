package com.typeless.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.ai.GroqWhisperClient
import com.typeless.ime.audio.AudioRecorderManager
import java.io.File
import kotlin.concurrent.thread

/**
 * TypelessInputMethodService (MyTypeless)
 * 
 * 核心語音輸入法服務：
 * 1. 雙階段管線：Groq Whisper 極速 STT + Gemini 純文字繁體去贅字。
 * 2. 來源透明可視化：狀態列即時標記 ⚡ Groq / 🤖 Gemini / 本機保底。
 * 3. 健全的硬體資源防護：支援 60s 上限、鍵盤收起自動切斷釋放。
 * 4. 具備原生 Backspace 刪除鍵（單擊刪除、長按連續退格）。
 * 5. 具備情境感知動作/送出鍵（依據焦點自動呈現 🔍 / 送出 / 前往 / ➜ / ✓ / ↵）。
 */
class TypelessInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "TypelessIME"
    }

    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var groqClient: GroqWhisperClient
    private lateinit var geminiClient: GeminiAudioClient

    private var isRecording = false
    private var touchDownTime = 0L
    private var wasRecordingAtDown = false

    private var tvStatus: TextView? = null
    private var pbAudioLevel: ProgressBar? = null
    private var btnRecord: Button? = null
    private var btnDelete: Button? = null
    private var btnAction: Button? = null
    private var lastEditorInfo: EditorInfo? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deleteHandler = Handler(Looper.getMainLooper())
    private val deleteRunnable = object : Runnable {
        override fun run() {
            handleDeleteSurroundingText()
            deleteHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioRecorderManager = AudioRecorderManager(this)
        settingsManager = SettingsManager(this)
        groqClient = GroqWhisperClient { settingsManager.groqApiKey }
        geminiClient = GeminiAudioClient { settingsManager.apiKey }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar
        )
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)

        tvStatus = view.findViewById(R.id.tv_status)
        pbAudioLevel = view.findViewById(R.id.pb_audio_level)
        btnRecord = view.findViewById(R.id.btn_record)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnAction = view.findViewById(R.id.btn_action)

        updateStatusPrompt()

        // 綁定送出／執行按鍵點擊
        btnAction?.setOnClickListener {
            handleActionButton()
        }

        // 綁定錄音按鈕觸控手勢
        btnRecord?.setOnTouchListener { _, event ->
            handleRecordTouch(event)
        }

        // 綁定退格刪除鍵（單擊刪除、長按連續退格）
        btnDelete?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    triggerHapticFeedback(30)
                    handleDeleteSurroundingText()
                    deleteHandler.postDelayed(deleteRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    deleteHandler.removeCallbacks(deleteRunnable)
                    true
                }
                else -> false
            }
        }

        // 開啟設定畫面
        val btnSettings = view.findViewById<Button>(R.id.btn_settings)
        btnSettings?.setOnClickListener {
            openSettingsActivity()
        }

        // 切換回系統其他輸入法按鈕
        val btnSwitch = view.findViewById<Button>(R.id.btn_switch_keyboard)
        btnSwitch?.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lastEditorInfo = info
        updateStatusPrompt()
        updateActionButtonState(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelCurrentRecording()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        cancelCurrentRecording()
    }

    private fun cancelCurrentRecording() {
        deleteHandler.removeCallbacks(deleteRunnable)
        if (isRecording) {
            isRecording = false
            audioRecorderManager.cancelRecording()
            btnRecord?.post {
                resetRecordButtonUi()
                updateStatusPrompt()
            }
        }
    }

    private fun updateStatusPrompt() {
        if (!settingsManager.hasGroqApiKey && !settingsManager.hasApiKey) {
            tvStatus?.text = "尚未設定 API Key，請點擊上方「⚙️ 設定」"
        } else {
            tvStatus?.text = getString(R.string.status_idle)
        }
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * 處理刪除按鍵邏輯
     */
    private fun handleDeleteSurroundingText() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    /**
     * 依據焦點輸入框之 EditorInfo 動態設定送出／執行按鍵圖示、文字與顏色
     */
    private fun updateActionButtonState(info: EditorInfo?) {
        val btn = btnAction ?: return
        if (info == null) {
            btn.text = getString(R.string.action_enter)
            btn.contentDescription = getString(R.string.desc_action_enter)
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_card))
            return
        }

        // 若有指定的 actionLabel，優先使用
        if (!info.actionLabel.isNullOrEmpty()) {
            btn.text = info.actionLabel.toString()
            btn.contentDescription = info.actionLabel.toString()
            btn.textSize = if (info.actionLabel.length > 2) 13f else 15f
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_action_active))
            return
        }

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnter = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        // 判斷是否應顯示動作型按鈕（搜尋、送出、前往、下一步、完成）
        if (!noEnter && action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_action_active))
            when (action) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    btn.text = getString(R.string.action_search)
                    btn.contentDescription = getString(R.string.desc_action_search)
                    btn.textSize = 20f
                }
                EditorInfo.IME_ACTION_SEND -> {
                    btn.text = getString(R.string.action_send)
                    btn.contentDescription = getString(R.string.desc_action_send)
                    btn.textSize = 15f
                }
                EditorInfo.IME_ACTION_GO -> {
                    btn.text = getString(R.string.action_go)
                    btn.contentDescription = getString(R.string.desc_action_go)
                    btn.textSize = 15f
                }
                EditorInfo.IME_ACTION_NEXT -> {
                    btn.text = getString(R.string.action_next)
                    btn.contentDescription = getString(R.string.desc_action_next)
                    btn.textSize = 18f
                }
                EditorInfo.IME_ACTION_DONE -> {
                    btn.text = getString(R.string.action_done)
                    btn.contentDescription = getString(R.string.desc_action_done)
                    btn.textSize = 18f
                }
                else -> {
                    btn.text = getString(R.string.action_enter)
                    btn.contentDescription = getString(R.string.desc_action_enter)
                    btn.textSize = 20f
                }
            }
        } else {
            // 多行編輯或一般換行
            btn.text = getString(R.string.action_enter)
            btn.contentDescription = getString(R.string.desc_action_enter)
            btn.textSize = 20f
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_card))
        }
    }

    /**
     * 處理送出／執行／確認按鍵點擊事件
     */
    private fun handleActionButton() {
        triggerHapticFeedback(30)
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: lastEditorInfo

        if (info != null) {
            // 1. 若應用程式指定了自訂 actionId
            if (info.actionId != 0) {
                ic.performEditorAction(info.actionId)
                return
            }

            // 2. 若為特定 IME 動作
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnter = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

            if (!noEnter && action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
                ic.performEditorAction(action)
                return
            }
        }

        // 3. 多行換行或一般 Enter 鍵
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * 處理錄音手勢（長按對講 / 短按開關）
     */
    private fun handleRecordTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = System.currentTimeMillis()
                wasRecordingAtDown = isRecording

                if (!isRecording) {
                    if (!settingsManager.hasGroqApiKey && !settingsManager.hasApiKey) {
                        Toast.makeText(this, "請先在設定中輸入 Groq 或 Gemini Key", Toast.LENGTH_SHORT).show()
                        openSettingsActivity()
                        return true
                    }

                    triggerHapticFeedback(45)

                    PermissionActivity.requestRecordAudio(this) { granted ->
                        if (granted) {
                            startRecordingFlow()
                        } else {
                            tvStatus?.text = "未授權麥克風，無法進行語音輸入"
                            Toast.makeText(this, "請允許麥克風權限以使用語音輸入", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    triggerHapticFeedback(55)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pressDuration = System.currentTimeMillis() - touchDownTime

                if (wasRecordingAtDown) {
                    stopRecordingFlow()
                } else {
                    if (pressDuration >= 400L) {
                        stopRecordingFlow()
                    } else {
                        tvStatus?.text = "🎙️ 錄音中... 再次點擊即可送出"
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!wasRecordingAtDown && isRecording) {
                    stopRecordingFlow()
                }
                return true
            }
        }
        return false
    }

    private fun startRecordingFlow() {
        val success = audioRecorderManager.startRecording(
            onAmplitude = { amplitude ->
                pbAudioLevel?.post {
                    val progress = (amplitude * 100).toInt().coerceIn(0, 100)
                    pbAudioLevel?.progress = progress
                }
            },
            onTimeout = {
                mainHandler.post {
                    if (isRecording) {
                        Toast.makeText(this@TypelessInputMethodService, "已達最大錄音保護上限，自動送出", Toast.LENGTH_SHORT).show()
                        stopRecordingFlow()
                    }
                }
            }
        )

        if (success) {
            isRecording = true
            tvStatus?.text = getString(R.string.status_recording)
            pbAudioLevel?.visibility = View.VISIBLE
            btnRecord?.text = getString(R.string.btn_record_stop)
            btnRecord?.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.ime_recording)
            )
        } else {
            tvStatus?.text = "麥克風啟動失敗，請確認未被佔用"
        }
    }

    private fun stopRecordingFlow() {
        val audioFile: File? = audioRecorderManager.stopRecording()
        isRecording = false
        triggerHapticFeedback(65)

        pbAudioLevel?.visibility = View.INVISIBLE
        pbAudioLevel?.progress = 0

        if (audioFile == null || !audioFile.exists() || audioFile.length() <= 100) {
            tvStatus?.text = "說話時間過短或未錄到聲音"
            resetRecordButtonUi()
            return
        }

        // 進入 AI 處理狀態
        btnRecord?.isEnabled = false
        btnRecord?.text = "⏳ 處理中..."
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_card)
        )

        thread(start = true, name = "TypelessProcessingThread") {
            processAudioPipeline(audioFile)
        }
    }

    /**
     * 雙階段管線處理邏輯
     */
    private fun processAudioPipeline(audioFile: File) {
        var rawText: String? = null

        // 階段 1：語音轉文字 (STT)
        if (settingsManager.hasGroqApiKey) {
            updateStatusOnMain("⚡ Groq 轉錄中...")
            val groqResult = groqClient.transcribe(audioFile)
            groqResult.onSuccess { text ->
                if (text.isNotBlank()) {
                    rawText = text
                }
            }.onFailure { err ->
                Log.w(TAG, "Groq 轉錄失敗: ${err.message}")
            }
        }

        // 若無 Groq Key 或 Groq 失敗，且有 Gemini Key，嘗試 Gemini 多模態直傳作為相容備援
        if (rawText.isNullOrBlank() && settingsManager.hasApiKey) {
            updateStatusOnMain("🤖 Gemini 直傳辨識中...")
            val directResult = geminiClient.transcribeAndPolish(audioFile, settingsManager.model)
            directResult.onSuccess { polished ->
                safeDelete(audioFile)
                onProcessingSuccess("🤖 Gemini 直傳完成", polished)
                return
            }.onFailure { err ->
                Log.w(TAG, "Gemini 音訊直傳失敗: ${err.message}")
            }
        }

        safeDelete(audioFile)

        if (rawText.isNullOrBlank()) {
            onProcessingFailure("未偵測到清晰語音或轉錄失敗")
            return
        }

        Log.i(TAG, "===> [Stage 1 STT Raw]: \"$rawText\"")

        // 階段 2：Gemini 純文字潤飾
        if (settingsManager.hasApiKey) {
            updateStatusOnMain("🤖 Gemini 潤飾中...")
            val polishResult = geminiClient.polishText(rawText!!, settingsManager.model)
            polishResult.onSuccess { polished ->
                val textToInject = if (polished.isNotBlank()) polished else rawText!!
                Log.i(TAG, "===> [Stage 2 Gemini Polished]: \"$textToInject\"")
                onProcessingSuccess("⚡ Groq + 🤖 Gemini 潤飾完成", textToInject)
            }.onFailure { err ->
                Log.w(TAG, "Gemini 潤飾失敗或冷卻 (429)，改用本機保底出字: ${err.message}")
                val localCleaned = cleanFillerWordsLocally(rawText!!)
                Log.i(TAG, "===> [Stage 2 Local Fallback]: \"$localCleaned\"")
                onProcessingSuccess("⚡ Groq 直出（AI 冷卻中）", localCleaned)
            }
        } else {
            // 沒有設定 Gemini Key，直接本機過濾出字
            val localCleaned = cleanFillerWordsLocally(rawText!!)
            Log.i(TAG, "===> [Stage 2 Direct]: \"$localCleaned\"")
            onProcessingSuccess("⚡ Groq 直出", localCleaned)
        }
    }

    private fun cleanFillerWordsLocally(text: String): String {
        var cleaned = text.trim()
        val fillerRegex = Regex("(^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)[，,。？?\\s]*)|([，,]\\s*(呃|啊|那個|就是說|嗯)\\s*)")
        cleaned = cleaned.replace(fillerRegex, " ").trim()
        return cleaned
    }

    private fun updateStatusOnMain(status: String) {
        mainHandler.post {
            tvStatus?.text = status
        }
    }

    private fun onProcessingSuccess(sourceStatus: String, text: String) {
        mainHandler.post {
            resetRecordButtonUi()
            if (text.isNotBlank()) {
                tvStatus?.text = sourceStatus
                injectText(text)
                triggerHapticFeedback(100)
            } else {
                tvStatus?.text = "未辨識出有效文字"
            }
        }
    }

    private fun onProcessingFailure(errorMsg: String) {
        mainHandler.post {
            resetRecordButtonUi()
            tvStatus?.text = "錯誤：$errorMsg"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetRecordButtonUi() {
        btnRecord?.isEnabled = true
        btnRecord?.text = getString(R.string.btn_record_start)
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_primary)
        )
    }

    private fun safeDelete(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun triggerHapticFeedback(durationMs: Long) {
        try {
            btnRecord?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun injectText(text: String) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.commitText(text, 1)
        } else {
            Toast.makeText(this, "無法取得焦點輸入框", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        deleteHandler.removeCallbacks(deleteRunnable)
        if (isRecording) {
            audioRecorderManager.cancelRecording()
        }
    }
}
