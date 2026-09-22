package com.typeless.ime
 
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/**
 * SettingsActivity
 * 
 * 讓使用者在手機介面上設定/更新 Groq 與 Gemini API Key。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)

        val etGroqApiKey = findViewById<TextInputEditText>(R.id.et_groq_api_key)
        val etApiKey = findViewById<TextInputEditText>(R.id.et_api_key)
        val btnGetGroqKey = findViewById<Button>(R.id.btn_get_groq_key)
        val btnGetGeminiKey = findViewById<Button>(R.id.btn_get_gemini_key)
        val btnSave = findViewById<Button>(R.id.btn_save)

        // 載入已儲存的金鑰
        settingsManager.groqApiKey?.let { etGroqApiKey.setText(it) }
        settingsManager.apiKey?.let { etApiKey.setText(it) }

        // 點擊前往申請 Groq Key
        btnGetGroqKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
            startActivity(intent)
        }

        // 點擊前往申請 Gemini Key
        btnGetGeminiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
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
}
