package com.typeless.ime
 
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.typeless.ime.ai.GeminiAudioClient
import com.typeless.ime.vocabulary.VocabularyRepository
import kotlin.concurrent.thread

/**
 * SettingsActivity
 * 
 * Allows users to configure and update their Groq and Gemini API keys.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var tvVocabStats: TextView
    private lateinit var vocabularyRepository: VocabularyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)
        vocabularyRepository = VocabularyRepository.getInstance(this)

        val etGroqApiKey = findViewById<TextInputEditText>(R.id.et_groq_api_key)
        val etApiKey = findViewById<TextInputEditText>(R.id.et_api_key)
        val btnGetGroqKey = findViewById<Button>(R.id.btn_get_groq_key)
        val btnGetGeminiKey = findViewById<Button>(R.id.btn_get_gemini_key)
        val btnManageVocab = findViewById<Button>(R.id.btn_manage_vocabulary)
        val btnSave = findViewById<Button>(R.id.btn_save)
        tvVocabStats = findViewById<TextView>(R.id.tv_vocab_stats)

        // Load saved API keys
        settingsManager.groqApiKey?.let { etGroqApiKey.setText(it) }
        settingsManager.apiKey?.let { etApiKey.setText(it) }

        // Open browser to obtain Groq API Key
        btnGetGroqKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
            startActivity(intent)
        }

        // Open browser to obtain Gemini API Key
        btnGetGeminiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
            startActivity(intent)
        }

        // Navigate to Vocabulary Management Activity
        btnManageVocab.setOnClickListener {
            val intent = Intent(this, VocabularyActivity::class.java)
            startActivity(intent)
        }

        btnSave.setOnClickListener {
            val groqKey = etGroqApiKey.text?.toString()?.trim() ?: ""
            val geminiKey = etApiKey.text?.toString()?.trim() ?: ""

            if (groqKey.isEmpty() && geminiKey.isEmpty()) {
                Toast.makeText(this, "請至少輸入一把有效的 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            settingsManager.groqApiKey = groqKey.ifEmpty { null }
            settingsManager.apiKey = geminiKey.ifEmpty { null }

            if (geminiKey.isNotEmpty()) {
                btnSave.isEnabled = false
                btnSave.text = "⏳ 正在實測 Gemini 連線..."
                val client = GeminiAudioClient(apiKeyProvider = { geminiKey })
                thread(start = true, name = "GeminiVerifyKeyThread") {
                    val verifyResult = client.verifyApiKeyAndModel(settingsManager.model)
                    Handler(Looper.getMainLooper()).post {
                        btnSave.isEnabled = true
                        btnSave.text = "儲存設定"
                        verifyResult.onSuccess { msg ->
                            notifyImeConnectionVerified()
                            tvVocabStats.text = "${baseStatsText()}\n✅ Gemini API 連線驗證成功 ($msg)"
                            Toast.makeText(this@SettingsActivity, "✅ Gemini API 連線驗證成功 ($msg)", Toast.LENGTH_LONG).show()
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinishing) finish()
                            }, 1500L)
                        }.onFailure { err ->
                            tvVocabStats.text = "${baseStatsText()}\n⚠️ Gemini API 驗證異常: ${err.message}"
                            Toast.makeText(this@SettingsActivity, "⚠️ Gemini API 驗證異常: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "設定已儲存！", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateVocabStats()
        runAutoConnectionCheck()
    }

    private fun baseStatsText(): String {
        val count = vocabularyRepository.getCount()
        val callsToday = settingsManager.dailyGeminiCalls
        val tokensToday = settingsManager.dailyGeminiTokens
        return "目前已收錄 $count 個專有名詞｜今日雲端 Gemini：$callsToday 次 ($tokensToday tok)"
    }

    private fun updateVocabStats() {
        tvVocabStats.text = baseStatsText()
    }

    private fun runAutoConnectionCheck() {
        val key = settingsManager.apiKey ?: return
        tvVocabStats.text = "${baseStatsText()}\n⏳ 正在實測 Gemini (${settingsManager.model}) 連線..."
        val client = GeminiAudioClient(apiKeyProvider = { key })
        thread(start = true, name = "GeminiAutoVerifyThread") {
            val result = client.verifyApiKeyAndModel(settingsManager.model)
            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { msg ->
                    notifyImeConnectionVerified()
                    tvVocabStats.text = "${baseStatsText()}\n✅ Gemini API 連線驗證成功 ($msg)"
                    Toast.makeText(this@SettingsActivity, "✅ Gemini API 連線驗證成功 (HTTP 200 OK)", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    tvVocabStats.text = "${baseStatsText()}\n⚠️ Gemini API 驗證失敗: ${err.message}"
                }
            }
        }
    }

    private fun notifyImeConnectionVerified() {
        val intent = Intent(TypelessInputMethodService.ACTION_CONNECTION_VERIFIED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
