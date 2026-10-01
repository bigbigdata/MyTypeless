package com.typeless.ime

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.ai.GroqWhisperClient
import com.typeless.ime.audio.AudioRecorderManager
import com.typeless.ime.engine.HybridPipelineCoordinator
import com.typeless.ime.engine.LocalAdaptivePolishingEngine
import com.typeless.ime.engine.NetworkStateMonitor
import com.typeless.ime.engine.PixelStreamingSttManager
import com.typeless.ime.vocabulary.VocabularyRepository
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
 * 6. Ergonomic Space key (single-tap space insertion and long-press continuous repeat).
 * 7. Abort / Cancel capability: Slide-up gesture to cancel during hold, and dynamic close icon to cancel anytime during recording/processing.
 */
class TypelessInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "TypelessIME"
        const val ON_DEVICE_MAX_RECORDING_MS = 10 * 60 * 1000L // 10-minute safety limit for on-device recognition
        private const val NOTIFICATION_CHANNEL_ID = "typeless_recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_AND_SUBMIT = "com.typeless.ime.ACTION_STOP_AND_SUBMIT"
        private const val ACTION_ABORT_RECORDING = "com.typeless.ime.ACTION_ABORT_RECORDING"
    }

    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var groqClient: GroqWhisperClient
    private lateinit var geminiClient: GeminiAudioClient
    private lateinit var vocabularyRepository: VocabularyRepository
    private lateinit var networkMonitor: NetworkStateMonitor
    private lateinit var hybridCoordinator: HybridPipelineCoordinator
    private lateinit var pixelSttManager: PixelStreamingSttManager
    private lateinit var localPolishingEngine: LocalAdaptivePolishingEngine

    private var isRecording = false
    private var isProcessing = false
    private var isSlidingToCancel = false
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var wasRecordingAtDown = false
    @Volatile
    private var activeProcessingJobId = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var keyboardRootView: View? = null
    private var lastTranscribedText: String? = null

    private var tvStatus: TextView? = null
    private var pbAudioLevel: ProgressBar? = null
    private var btnRecord: MaterialButton? = null
    private var btnSpace: MaterialButton? = null
    private var btnDelete: MaterialButton? = null
    private var btnAction: MaterialButton? = null
    private var btnModeToggle: Button? = null
    private var lastEditorInfo: EditorInfo? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deleteHandler = Handler(Looper.getMainLooper())
    private val deleteRunnable = object : Runnable {
        override fun run() {
            handleDeleteSurroundingText()
            deleteHandler.postDelayed(this, 50L)
        }
    }
    private val spaceHandler = Handler(Looper.getMainLooper())
    private val spaceRunnable = object : Runnable {
        override fun run() {
            handleSpace()
            spaceHandler.postDelayed(this, 60L)
        }
    }

    private val onDeviceSafetyTimeoutRunnable = Runnable {
        if (isRecording) {
            Toast.makeText(this@TypelessInputMethodService, "已達端側語音辨識上限 (10分鐘)，自動送出", Toast.LENGTH_SHORT).show()
            stopRecordingFlow()
        }
    }

    private val recordingControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_AND_SUBMIT -> {
                    Log.d(TAG, "Notification action: STOP_AND_SUBMIT")
                    if (isRecording) {
                        stopRecordingFlow()
                    }
                }
                ACTION_ABORT_RECORDING -> {
                    Log.d(TAG, "Notification action: ABORT_RECORDING")
                    if (isRecording) {
                        abortRecordingFlow(getString(R.string.status_recording_aborted))
                    } else if (isProcessing) {
                        abortProcessingFlow()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioRecorderManager = AudioRecorderManager(this)
        settingsManager = SettingsManager(this)
        vocabularyRepository = VocabularyRepository.getInstance(this)
        groqClient = GroqWhisperClient { settingsManager.groqApiKey }
        geminiClient = GeminiAudioClient { settingsManager.apiKey }
        networkMonitor = NetworkStateMonitor(this)
        pixelSttManager = PixelStreamingSttManager(this)
        localPolishingEngine = LocalAdaptivePolishingEngine(
            context = this,
            geminiClient = geminiClient,
            networkMonitor = networkMonitor,
            settingsManager = settingsManager
        )
        hybridCoordinator = HybridPipelineCoordinator(
            context = this,
            networkMonitor = networkMonitor,
            settingsManager = settingsManager,
            vocabularyRepository = vocabularyRepository,
            groqClient = groqClient,
            geminiClient = geminiClient
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_AND_SUBMIT)
            addAction(ACTION_ABORT_RECORDING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingControlReceiver, filter)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar
        )
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)
        keyboardRootView = view

        tvStatus = view.findViewById(R.id.tv_status)
        pbAudioLevel = view.findViewById(R.id.pb_audio_level)
        btnRecord = view.findViewById(R.id.btn_record)
        btnSpace = view.findViewById(R.id.btn_space)
        btnDelete = view.findViewById(R.id.btn_delete)
        btnAction = view.findViewById(R.id.btn_action)

        // Ensure Material vector icons and tints are applied to Space and Delete keys
        btnSpace?.setIconResource(R.drawable.ic_space_bar)
        btnSpace?.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_text_primary))
        btnSpace?.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START

        btnDelete?.setIconResource(R.drawable.ic_backspace)
        btnDelete?.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ime_text_primary))
        btnDelete?.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START

        // Bind mode toggle button (Cloud vs Pixel On-Device)
        btnModeToggle = view.findViewById(R.id.btn_mode_toggle)
        updateModeToggleButtonUi()
        btnModeToggle?.setOnClickListener {
            settingsManager.isForcedOfflineMode = !settingsManager.isForcedOfflineMode
            updateModeToggleButtonUi()
            val modeStr = if (settingsManager.isForcedOfflineMode) "📱 Pixel 端側模式" else "☁️ 雲端優先模式"
            Toast.makeText(this, "已切換為：$modeStr", Toast.LENGTH_SHORT).show()
        }

        updateStatusPrompt()

        // Bind action / enter button click
        btnAction?.setOnClickListener {
            handleActionButton()
        }

        // Bind voice recording toggle click (Pure Tap-to-Toggle mode)
        btnRecord?.setOnClickListener {
            handleRecordToggleClick()
        }

        // Bind space key (single-tap space, long-press continuous space)
        btnSpace?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    triggerHapticFeedback(30)
                    handleSpace()
                    spaceHandler.postDelayed(spaceRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    spaceHandler.removeCallbacks(spaceRunnable)
                    true
                }
                else -> false
            }
        }

        // Bind backspace key (single-tap delete, long-press continuous backspace; abort recording/processing if active)
        btnDelete?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isRecording) {
                        abortRecordingFlow(getString(R.string.status_recording_aborted))
                        return@setOnTouchListener true
                    }
                    if (isProcessing) {
                        abortProcessingFlow()
                        return@setOnTouchListener true
                    }
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
        updateActionButtonState(info)

        if (isRecording) {
            syncRecordingUiState()
        } else if (isProcessing) {
            syncProcessingUiState()
        } else {
            updateStatusPrompt()
            resetRecordButtonUi()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        deleteHandler.removeCallbacks(deleteRunnable)
        spaceHandler.removeCallbacks(spaceRunnable)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        deleteHandler.removeCallbacks(deleteRunnable)
        spaceHandler.removeCallbacks(spaceRunnable)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTypeless:RecordingWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(15 * 60 * 1000L) // Safety max 15 mins
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wakeLock", e)
        }
    }

    private fun setKeepScreenOn(keepOn: Boolean) {
        keyboardRootView?.keepScreenOn = keepOn
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "語音錄製中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MyTypeless 語音輸入背景錄製與控制"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun showRecordingNotification() {
        createNotificationChannel()

        val stopIntent = Intent(ACTION_STOP_AND_SUBMIT).apply { setPackage(packageName) }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val abortIntent = Intent(ACTION_ABORT_RECORDING).apply { setPackage(packageName) }
        val abortPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("MyTypeless 錄音中🎙️")
            .setContentText("正在持續收音... 點擊下方按鈕可隨時完成或放棄")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "⏹️ 停止並送出", stopPendingIntent)
            .addAction(0, "✕ 放棄", abortPendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun dismissRecordingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
    }

    private fun syncRecordingUiState() {
        val isDeviceMode = settingsManager.isForcedOfflineMode || !networkMonitor.isOnline
        tvStatus?.text = if (isDeviceMode) "🎙️ 聆聽中... 說完整句後請點擊送出" else getString(R.string.status_recording)
        pbAudioLevel?.visibility = View.VISIBLE
        btnRecord?.isEnabled = true
        btnRecord?.text = getString(R.string.btn_record_stop)
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_recording)
        )
        updateDeleteButtonToCancelMode()
        setKeepScreenOn(true)
    }

    private fun syncProcessingUiState() {
        val isDeviceMode = settingsManager.isForcedOfflineMode || !networkMonitor.isOnline
        tvStatus?.text = if (isDeviceMode) "⏳ Pixel 處理中..." else "⏳ 處理中..."
        pbAudioLevel?.visibility = View.INVISIBLE
        btnRecord?.isEnabled = false
        btnRecord?.text = "⏳ 處理中..."
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_card)
        )
        updateDeleteButtonToCancelMode()
        setKeepScreenOn(false)
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
     * Handles space key insertion.
     */
    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        ic.commitText(" ", 1)
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
     * Handles Pure Tap-to-Toggle recording:
     * - Tap to start recording (runs until user explicitly taps again or safety timeout).
     * - Tap again to stop and submit.
     */
    private fun handleRecordToggleClick() {
        if (isProcessing) return

        if (!isRecording) {
            val isDeviceMode = settingsManager.isForcedOfflineMode || !networkMonitor.isOnline
            if (!isDeviceMode && !settingsManager.hasGroqApiKey && !settingsManager.hasApiKey) {
                Toast.makeText(this, "請先在設定中輸入 Groq 或 Gemini Key", Toast.LENGTH_SHORT).show()
                openSettingsActivity()
                return
            }

            if (!isDeviceMode) {
                groqClient.prewarmConnection()
                geminiClient.prewarmConnection()
            }

            triggerHapticFeedback(45)

            PermissionActivity.requestRecordAudio(this) { granted ->
                if (granted) {
                    if (isDeviceMode) {
                        startOnDeviceRecordingFlow()
                    } else {
                        startRecordingFlow()
                    }
                } else {
                    tvStatus?.text = "未授權麥克風，無法進行語音輸入"
                    Toast.makeText(this, "請允許麥克風權限以使用語音輸入", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Already recording -> User clicked stop to finalize speech!
            triggerHapticFeedback(65)
            stopRecordingFlow()
        }
    }

    private fun startRecordingFlow() {
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)

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
                        Toast.makeText(this@TypelessInputMethodService, "已達最大錄音保護上限 (15分鐘)，自動送出", Toast.LENGTH_SHORT).show()
                        stopRecordingFlow()
                    }
                }
            }
        )

        if (success) {
            acquireWakeLock()
            setKeepScreenOn(true)
            showRecordingNotification()

            isRecording = true
            isSlidingToCancel = false
            tvStatus?.text = getString(R.string.status_recording)
            pbAudioLevel?.visibility = View.VISIBLE
            btnRecord?.text = getString(R.string.btn_record_stop)
            btnRecord?.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.ime_recording)
            )
            updateDeleteButtonToCancelMode()
        } else {
            tvStatus?.text = "麥克風啟動失敗，請確認未被佔用"
        }
    }

    private fun stopRecordingFlow() {
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)
        releaseWakeLock()
        setKeepScreenOn(false)
        dismissRecordingNotification()

        val isDeviceMode = settingsManager.isForcedOfflineMode || !networkMonitor.isOnline
        if (isDeviceMode) {
            stopOnDeviceRecordingFlow()
            return
        }

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
        isProcessing = true
        btnRecord?.isEnabled = false
        btnRecord?.text = "⏳ 處理中..."
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_card)
        )
        updateDeleteButtonToCancelMode()

        val jobId = ++activeProcessingJobId
        thread(start = true, name = "TypelessProcessingThread") {
            processAudioPipeline(audioFile, jobId)
        }
    }

    private fun startOnDeviceRecordingFlow() {
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)
        mainHandler.postDelayed(onDeviceSafetyTimeoutRunnable, ON_DEVICE_MAX_RECORDING_MS)

        val jobId = ++activeProcessingJobId
        val started = pixelSttManager.startListening(
            onRmsChanged = { rmsdB ->
                pbAudioLevel?.post {
                    val progress = ((rmsdB + 2f) * 10f).toInt().coerceIn(0, 100)
                    pbAudioLevel?.progress = progress
                }
            },
            onPartialResult = { partial ->
                tvStatus?.post {
                    tvStatus?.text = "🗣️ 正在辨識: $partial"
                }
            },
            onFinalResult = { rawText, asrDurationMs ->
                mainHandler.post {
                    processOnDeviceStage2(rawText, asrDurationMs, jobId)
                }
            },
            onError = { errorMsg ->
                mainHandler.post {
                    abortProcessingFlow("❌ $errorMsg")
                }
            }
        )

        if (started) {
            acquireWakeLock()
            setKeepScreenOn(true)
            showRecordingNotification()

            isRecording = true
            isSlidingToCancel = false
            tvStatus?.text = "🎙️ 聆聽中... 說完整句後請點擊送出"
            pbAudioLevel?.visibility = View.VISIBLE
            btnRecord?.text = getString(R.string.btn_record_stop)
            btnRecord?.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.ime_recording)
            )
            updateDeleteButtonToCancelMode()
        } else {
            tvStatus?.text = "原生離線語音啟動失敗"
            resetRecordButtonUi()
        }
    }

    private fun stopOnDeviceRecordingFlow() {
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)
        releaseWakeLock()
        setKeepScreenOn(false)
        dismissRecordingNotification()

        isRecording = false
        triggerHapticFeedback(65)
        pbAudioLevel?.visibility = View.INVISIBLE
        pbAudioLevel?.progress = 0

        isProcessing = true
        btnRecord?.isEnabled = false
        btnRecord?.text = "⏳ Pixel 處理中..."
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_card)
        )
        updateDeleteButtonToCancelMode()

        pixelSttManager.stopListeningByUser()
    }

    private fun processOnDeviceStage2(rawText: String, asrDurationMs: Long, jobId: Long) {
        if (jobId != activeProcessingJobId) return

        isProcessing = true
        tvStatus?.text = "📱 Pixel 端側潤飾中..."

        thread(start = true, name = "OnDevicePolishingThread") {
            val polishStartTime = System.currentTimeMillis()
            val terms = vocabularyRepository.getAllTermsForGemini(limit = 40)
            val polishResult = localPolishingEngine.polish(rawText, terms)
            val polishDurationMs = System.currentTimeMillis() - polishStartTime
            val totalMs = asrDurationMs + polishDurationMs

            val finalText = polishResult.getOrDefault(rawText)

            if (jobId != activeProcessingJobId) return@thread

            // Asynchronously record usage and auto-harvest terms in existing SQLite dictionary
            vocabularyRepository.recordUsageAsync(finalText)

            mainHandler.post {
                injectText(finalText)
                val badge = "📱 端側完成｜ASR: ${asrDurationMs}ms ➔ 潤飾: ${polishDurationMs}ms (總計 ${totalMs}ms)"
                tvStatus?.text = badge
                Toast.makeText(this@TypelessInputMethodService, badge, Toast.LENGTH_SHORT).show()
                triggerHapticFeedback(40)
                resetRecordButtonUi()
                isProcessing = false
            }
        }
    }

    private fun updateModeToggleButtonUi() {
        if (settingsManager.isForcedOfflineMode) {
            btnModeToggle?.text = "📱 端側"
            btnModeToggle?.setTextColor(ContextCompat.getColor(this, R.color.ime_recording))
        } else {
            btnModeToggle?.text = "☁️ 雲端"
            btnModeToggle?.setTextColor(ContextCompat.getColor(this, R.color.ime_text_secondary))
        }
    }

    /**
     * Aborts and discards the active audio recording without transcription.
     */
    private fun abortRecordingFlow(statusMessage: String = "已中斷並放棄本次錄音") {
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)
        releaseWakeLock()
        setKeepScreenOn(false)
        dismissRecordingNotification()

        isRecording = false
        isSlidingToCancel = false
        audioRecorderManager.cancelRecording()
        pixelSttManager.cancel()
        triggerHapticFeedback(80)

        mainHandler.post {
            pbAudioLevel?.visibility = View.INVISIBLE
            pbAudioLevel?.progress = 0
            resetRecordButtonUi()
            tvStatus?.text = statusMessage
            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Aborts the in-flight AI processing job and prevents text injection.
     */
    private fun abortProcessingFlow(statusMessage: String = "已中斷輸入") {
        releaseWakeLock()
        setKeepScreenOn(false)
        dismissRecordingNotification()

        activeProcessingJobId++
        isProcessing = false
        triggerHapticFeedback(80)

        mainHandler.post {
            resetRecordButtonUi()
            tvStatus?.text = statusMessage
            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDeleteButtonToCancelMode() {
        btnDelete?.setIconResource(R.drawable.ic_close)
        btnDelete?.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_recording)
        )
        btnDelete?.contentDescription = getString(R.string.desc_cancel)
    }

    private fun resetDeleteButtonUi() {
        btnDelete?.setIconResource(R.drawable.ic_backspace)
        btnDelete?.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_text_primary)
        )
        btnDelete?.contentDescription = getString(R.string.desc_delete)
    }

    /**
     * Two-stage pipeline processing delegated to HybridPipelineCoordinator
     * (Coordinates cloud Whisper/Gemini with on-device fallback and circuit breakers).
     */
    private fun processAudioPipeline(audioFile: File, jobId: Long) {
        val result = hybridCoordinator.process(
            audioFile = audioFile,
            jobId = jobId,
            activeJobCheck = { jobId == activeProcessingJobId },
            onStatusUpdate = { status -> updateStatusOnMain(status) }
        )

        safeDelete(audioFile)

        if (jobId != activeProcessingJobId) return

        result.onSuccess { output ->
            onProcessingSuccess(output.statusBadge, output.text, jobId)
        }.onFailure { err ->
            onProcessingFailure(err.message ?: "轉錄或整理失敗", jobId)
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

    private fun onProcessingSuccess(sourceStatus: String, text: String, jobId: Long) {
        if (jobId != activeProcessingJobId) return
        isProcessing = false
        if (text.isNotBlank()) {
            vocabularyRepository.recordUsageAsync(text)
        }
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

    private fun onProcessingFailure(errorMsg: String, jobId: Long) {
        if (jobId != activeProcessingJobId) return
        isProcessing = false
        mainHandler.post {
            resetRecordButtonUi()
            tvStatus?.text = "錯誤：$errorMsg"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetRecordButtonUi() {
        isProcessing = false
        isRecording = false
        isSlidingToCancel = false
        btnRecord?.isEnabled = true
        btnRecord?.text = getString(R.string.btn_record_start)
        btnRecord?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_primary)
        )
        resetDeleteButtonUi()
        setKeepScreenOn(false)
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
        val traditionalText = com.typeless.ime.engine.TraditionalChineseConverter.toTraditional(text)
        lastTranscribedText = traditionalText
        val ic = currentInputConnection
        if (ic != null) {
            ic.commitText(traditionalText, 1)
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("MyTypeless Transcribed Text", traditionalText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "語音辨識完成！已自動複製至剪貼簿：\n$traditionalText", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(onDeviceSafetyTimeoutRunnable)
        networkMonitor.unregister()
        pixelSttManager.cancel()
        deleteHandler.removeCallbacks(deleteRunnable)
        spaceHandler.removeCallbacks(spaceRunnable)
        releaseWakeLock()
        dismissRecordingNotification()
        try {
            unregisterReceiver(recordingControlReceiver)
        } catch (_: Exception) {}
        if (isRecording) {
            audioRecorderManager.cancelRecording()
        }
    }
}
