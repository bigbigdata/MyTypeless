package com.typeless.ime

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.typeless.ime.vocabulary.VocabularyItem
import com.typeless.ime.vocabulary.VocabularyRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VocabularyActivity
 *
 * Dedicated management screen for personalized custom vocabulary and technical terminology.
 * Supports real-time search, manual word addition, usage deletion, and factory default restoration.
 */
class VocabularyActivity : AppCompatActivity() {

    private lateinit var repository: VocabularyRepository
    private lateinit var adapter: VocabularyAdapter

    private lateinit var rvVocabulary: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnResetDefaults: Button
    private lateinit var fabAddTerm: ExtendedFloatingActionButton
    private lateinit var tvHeaderHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)

        repository = VocabularyRepository.getInstance(this)

        initViews()
        setupRecyclerView()
        setupListeners()
        loadVocabulary()
    }

    private fun initViews() {
        rvVocabulary = findViewById(R.id.rv_vocabulary)
        tvEmpty = findViewById(R.id.tv_empty)
        etSearch = findViewById(R.id.et_search_vocab)
        btnBack = findViewById(R.id.btn_back)
        btnResetDefaults = findViewById(R.id.btn_reset_defaults)
        fabAddTerm = findViewById(R.id.fab_add_term)
        tvHeaderHint = findViewById(R.id.tv_vocab_header_hint)
    }

    private fun setupRecyclerView() {
        adapter = VocabularyAdapter(
            onDeleteClick = { item -> confirmDeleteItem(item) }
        )
        rvVocabulary.layoutManager = LinearLayoutManager(this)
        rvVocabulary.adapter = adapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnResetDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重設為預設詞庫")
                .setMessage("確定要將詞庫重設為出廠精選種子清單嗎？你自行新增的詞彙將會被清除。")
                .setPositiveButton("重設") { _, _ ->
                    repository.resetToDefaults()
                    loadVocabulary()
                    Toast.makeText(this, "已恢復預設詞庫", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fabAddTerm.setOnClickListener { showAddTermDialog() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadVocabulary(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadVocabulary(searchQuery: String? = null) {
        val items = repository.getAllItems(searchQuery)
        adapter.submitList(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val totalCount = repository.getCount()
        tvHeaderHint.text = "共收錄 $totalCount 個詞彙（含即時自動收錄）。動態挑選前 40 高頻詞注入 Whisper，前 150 詞注入 Gemini 模糊校正。"
    }

    private fun showAddTermDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etTerm = EditText(context).apply {
            hint = "專有名詞或單字（例如：Notion, PR, 串接）"
            textSize = 15f
        }
        layout.addView(etTerm)

        val etTag = EditText(context).apply {
            hint = "標籤類別（選填，例如：Tech, PM, Daily）"
            textSize = 14f
        }
        layout.addView(etTag)

        AlertDialog.Builder(context)
            .setTitle("新增個人詞彙")
            .setView(layout)
            .setPositiveButton("新增") { _, _ ->
                val term = etTerm.text.toString().trim()
                val tag = etTag.text.toString().trim().ifEmpty { "General" }
                if (term.isNotEmpty()) {
                    repository.insertOrIncrement(term, tag)
                    loadVocabulary(etSearch.text?.toString())
                    Toast.makeText(context, "已新增「$term」", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "詞彙不能為空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteItem(item: VocabularyItem) {
        AlertDialog.Builder(this)
            .setTitle("刪除詞彙")
            .setMessage("確定要從詞庫中移除「${item.term}」嗎？")
            .setPositiveButton("刪除") { _, _ ->
                repository.deleteItem(item.id)
                loadVocabulary(etSearch.text?.toString())
                Toast.makeText(this, "已移除「${item.term}」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * RecyclerView Adapter for vocabulary list
     */
    private class VocabularyAdapter(
        private val onDeleteClick: (VocabularyItem) -> Unit
    ) : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

        private val items = mutableListOf<VocabularyItem>()
        private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        fun submitList(newItems: List<VocabularyItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vocabulary, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTerm.text = item.term
            
            when (item.tag) {
                "Auto" -> {
                    holder.tvTag.text = "自動收錄"
                    holder.tvTag.setBackgroundColor(android.graphics.Color.parseColor("#1B4D3E"))
                    holder.tvTag.setTextColor(android.graphics.Color.parseColor("#80CBC4"))
                }
                "Tech" -> {
                    holder.tvTag.text = "Tech"
                    holder.tvTag.setBackgroundColor(android.graphics.Color.parseColor("#382C5A"))
                    holder.tvTag.setTextColor(android.graphics.Color.parseColor("#D0BCFF"))
                }
                "Daily" -> {
                    holder.tvTag.text = "Daily"
                    holder.tvTag.setBackgroundColor(android.graphics.Color.parseColor("#4A3718"))
                    holder.tvTag.setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                }
                else -> {
                    holder.tvTag.text = item.tag
                    holder.tvTag.setBackgroundColor(android.graphics.Color.parseColor("#2E3842"))
                    holder.tvTag.setTextColor(android.graphics.Color.parseColor("#90CAF9"))
                }
            }

            val dateStr = dateFormat.format(Date(item.lastUsedAt))
            holder.tvStats.text = "使用: ${item.frequency} 次  •  最近: $dateStr"
            holder.btnDelete.setOnClickListener { onDeleteClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTerm: TextView = itemView.findViewById(R.id.tv_term)
            val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
            val tvStats: TextView = itemView.findViewById(R.id.tv_stats)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_term)
        }
    }
}
