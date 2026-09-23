package com.typeless.ime
 
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.typeless.ime.vocabulary.VocabularyRepository

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

            Toast.makeText(this, "設定已儲存！", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateVocabStats()
    }

    private fun updateVocabStats() {
        val count = vocabularyRepository.getCount()
        tvVocabStats.text = "目前已收錄 $count 個個人專有名詞與高頻術語"
    }
}
