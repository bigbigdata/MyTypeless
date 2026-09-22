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
 * Core voice input method service:
 * 1. Two-stage pipeline: Groq Whisper ultra-fast STT + Gemini text polish and filler removal.
 * 2. Transparent source indication: Real-time status badge showing Groq, Gemini, or local fallback.
 * 3. Robust hardware lifecycle protection: Audio session termination on keyboard hide.
 * 4. Native backspace key (single tap delete, long-press continuous backspace).
 * 5. Context-aware action/enter key (dynamically displays Search, Send, Go, Next, Done, or Newline based on EditorInfo).
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

        // Bind action / enter button click
        btnAction?.setOnClickListener {
            handleActionButton()
        }

        // Bind voice recording touch gestures
        btnRecord?.setOnTouchListener { _, event ->
            handleRecordTouch(event)
        }

        // Bind backspace key (single-tap delete, long-press continuous backspace)
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

        // Open settings activity
        val btnSettings = view.findViewById<Button>(R.id.btn_settings)
        btnSettings?.setOnClickListener {
            openSettingsActivity()
        }

        // Switch to other system keyboards
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
     * Handles backspace delete logic.
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
     * Dynamically configures the action button icon, label, and style based on EditorInfo.
     */
    private fun updateActionButtonState(info: EditorInfo?) {
        val btn = btnAction ?: return
        if (info == null) {
            btn.text = getString(R.string.action_enter)
            btn.contentDescription = getString(R.string.desc_action_enter)
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_card))
            return
        }

        // Prioritize custom actionLabel if provided by the target editor
        if (!info.actionLabel.isNullOrEmpty()) {
            btn.text = info.actionLabel.toString()
            btn.contentDescription = info.actionLabel.toString()
            btn.textSize = if (info.actionLabel.length > 2) 13f else 15f
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_action_active))
            return
        }

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnter = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        // Determine whether to display an active action button (Search, Send, Go, Next, Done)
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
            // Multiline editor or default enter
            btn.text = getString(R.string.action_enter)
            btn.contentDescription = getString(R.string.desc_action_enter)
            btn.textSize = 20f
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_card))
        }
    }

    /**
     * Handles action / enter button click events.
     */
    private fun handleActionButton() {
        triggerHapticFeedback(30)
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: lastEditorInfo

        if (info != null) {
            // 1. If target application specifies a custom actionId
            if (info.actionId != 0) {
                ic.performEditorAction(info.actionId)
                return
            }

            // 2. If valid standard IME action
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnter = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

            if (!noEnter && action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
                ic.performEditorAction(action)
                return
            }
        }

        // 3. Multiline newline or standard Enter key event
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Handles recording touch gestures (hold to talk / tap to toggle).
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

                    // Pre-warm connection pool asynchronously to eliminate TLS handshake latency
                    groqClient.prewarmConnection()
                    geminiClient.prewarmConnection()

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

        // Transition to AI processing state
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
     * Two-stage pipeline processing logic:
     * Stage 1: Audio STT (Groq Whisper / Gemini Audio direct fallback)
     * Stage 2: Text Polishing (Gemini LLM / Local Fast-Path filter)
     */
    private fun processAudioPipeline(audioFile: File) {
        var rawText: String? = null

        // Stage 1: Speech-to-Text (STT) via Groq Whisper
        if (settingsManager.hasGroqApiKey) {
            updateStatusOnMain("⚡ Groq 轉錄中...")
            val groqResult = groqClient.transcribe(audioFile)
            groqResult.onSuccess { text ->
                if (text.isNotBlank()) {
                    rawText = text
                }
            }.onFailure { err ->
                Log.w(TAG, "Groq transcription failed: ${err.message}")
            }
        }

        // Fallback: If no Groq Key is available or Groq STT fails, try direct Gemini multimodal transcription if Gemini Key is configured
        if (rawText.isNullOrBlank() && settingsManager.hasApiKey) {
            updateStatusOnMain("🤖 Gemini 直傳辨識中...")
            val directResult = geminiClient.transcribeAndPolish(audioFile, settingsManager.model)
            directResult.onSuccess { polished ->
                safeDelete(audioFile)
                onProcessingSuccess("🤖 Gemini 直傳完成", polished)
                return
            }.onFailure { err ->
                Log.w(TAG, "Gemini direct audio transcription failed: ${err.message}")
            }
        }

        safeDelete(audioFile)

        if (rawText.isNullOrBlank()) {
            onProcessingFailure("未偵測到清晰語音或轉錄失敗")
            return
        }

        Log.i(TAG, "===> [Stage 1 STT Raw]: \"$rawText\"")

        // Latency optimization: Fast-Path Skip for ultra-short affirmations.
        // For common 1-4 character affirmations (e.g., "OK", "Sure", "Thanks", "No problem"),
        // inject text directly locally to save 500ms+ cloud Gemini round-trip latency.
        if (isFastPathCandidate(rawText!!)) {
            val localCleaned = cleanFillerWordsLocally(rawText!!)
            val formatted = applyPanguSpacing(localCleaned)
            Log.i(TAG, "===> [Fast-Path Direct]: \"$formatted\"")
            onProcessingSuccess("⚡ 極速直出 (Fast-Path)", formatted)
            return
        }

        // Stage 2: Gemini text polishing and structuring
        if (settingsManager.hasApiKey) {
            updateStatusOnMain("🤖 Gemini 潤飾中...")
            val polishResult = geminiClient.polishText(rawText!!, settingsManager.model)
            polishResult.onSuccess { polished ->
                val textToInject = if (polished.isNotBlank()) polished else rawText!!
                Log.i(TAG, "===> [Stage 2 Gemini Polished]: \"$textToInject\"")
                onProcessingSuccess("⚡ Groq + 🤖 Gemini 潤飾完成", textToInject)
            }.onFailure { err ->
                Log.w(TAG, "Gemini polishing failed or rate limited (429), falling back to local output: ${err.message}")
                val localCleaned = cleanFillerWordsLocally(rawText!!)
                Log.i(TAG, "===> [Stage 2 Local Fallback]: \"$localCleaned\"")
                onProcessingSuccess("⚡ Groq 直出（AI 冷卻中）", localCleaned)
            }
        } else {
            // If no Gemini API key is configured, perform local filtering and output directly
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

    /**
     * Determines if the transcribed text qualifies for Fast-Path bypass
     * (high-frequency, unambiguous 1-4 character affirmations).
     */
    private fun isFastPathCandidate(text: String): Boolean {
        val trimmed = text.trim()
        val fastRegex = Regex("^(好|好的|好啊|可以|沒問題|没问题|收到|謝謝|谢谢|對|对|OK|ok|yes|Yes|好沒問題|好的謝謝|收到謝謝)[，,。！？!~]*$")
        return fastRegex.matches(trimmed)
    }

    /**
     * Pangu Spacing: Automatically inserts appropriate spacing between CJK and Western/numeric characters.
     */
    private fun applyPanguSpacing(text: String): String {
        var result = text
        // CJK followed by alphanumeric
        result = result.replace(Regex("([\\u4e00-\\u9fa5])([a-zA-Z0-9])"), "$1 $2")
        // Alphanumeric followed by CJK
        result = result.replace(Regex("([a-zA-Z0-9])([\\u4e00-\\u9fa5])"), "$1 $2")
        return result.trim()
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
                val formatted = applyPanguSpacing(text)
                tvStatus?.text = sourceStatus
                injectText(formatted)
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
