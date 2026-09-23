package com.typeless.ime.vocabulary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * VocabularyDatabaseHelper
 *
 * Native, lightweight SQLite storage for personalized custom vocabulary and terminology.
 * Optimized for microsecond queries and dynamic bias selection.
 */
class VocabularyDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "VocabularyDbHelper"
        const val DATABASE_NAME = "typeless_vocabulary.db"
        const val DATABASE_VERSION = 1

        const val TABLE_NAME = "vocabulary"
        const val COLUMN_ID = "id"
        const val COLUMN_TERM = "term"
        const val COLUMN_TAG = "tag"
        const val COLUMN_FREQUENCY = "frequency"
        const val COLUMN_LAST_USED_AT = "last_used_at"
        const val COLUMN_IS_DEFAULT = "is_default"

        /**
         * Curated seed terms covering common daily, engineering, and PM code-switching expressions.
         */
        val DEFAULT_SEEDS = listOf(
            Pair("PR", "Tech"),
            Pair("deploy", "Tech"),
            Pair("meeting", "General"),
            Pair("sync", "Tech"),
            Pair("check", "General"),
            Pair("chill", "Daily"),
            Pair("brunch", "Daily"),
            Pair("bug", "Tech"),
            Pair("commit", "Tech"),
            Pair("branch", "Tech"),
            Pair("merge", "Tech"),
            Pair("feature", "Tech"),
            Pair("API", "Tech"),
            Pair("SDK", "Tech"),
            Pair("PM", "Tech"),
            Pair("UI", "Tech"),
            Pair("UX", "Tech"),
            Pair("issue", "Tech"),
            Pair("release", "Tech"),
            Pair("test", "Tech"),
            Pair("Wi-Fi", "Daily"),
            Pair("Google", "Tech"),
            Pair("GitHub", "Tech"),
            Pair("Notion", "Tech"),
            Pair("Slack", "Tech"),
            Pair("Docker", "Tech"),
            Pair("Kubernetes", "Tech"),
            Pair("CI/CD", "Tech"),
            Pair("backend", "Tech"),
            Pair("frontend", "Tech"),
            Pair("production", "Tech"),
            Pair("staging", "Tech"),
            Pair("refactor", "Tech"),
            Pair("benchmark", "Tech"),
            Pair("台積電", "Business"),
            Pair("FinTech", "Business")
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSql = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TERM TEXT NOT NULL UNIQUE COLLATE NOCASE,
                $COLUMN_TAG TEXT NOT NULL DEFAULT 'General',
                $COLUMN_FREQUENCY INTEGER NOT NULL DEFAULT 1,
                $COLUMN_LAST_USED_AT INTEGER NOT NULL,
                $COLUMN_IS_DEFAULT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTableSql)

        db.execSQL("CREATE INDEX idx_vocab_freq_recent ON $TABLE_NAME ($COLUMN_FREQUENCY DESC, $COLUMN_LAST_USED_AT DESC)")
        db.execSQL("CREATE INDEX idx_vocab_term ON $TABLE_NAME ($COLUMN_TERM)")

        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle migrations in future versions
    }

    private fun seedDefaults(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((term, tag) in DEFAULT_SEEDS) {
                val cv = ContentValues().apply {
                    put(COLUMN_TERM, term)
                    put(COLUMN_TAG, tag)
                    put(COLUMN_FREQUENCY, 1)
                    put(COLUMN_LAST_USED_AT, now)
                    put(COLUMN_IS_DEFAULT, 1)
                }
                db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "Pre-seeded ${DEFAULT_SEEDS.size} default vocabulary terms")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Retrieves top terms ordered by usage frequency and recency.
     */
    fun getTopTerms(limit: Int = 35): List<String> {
        val list = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_TERM),
            null,
            null,
            null,
            null,
            "$COLUMN_FREQUENCY DESC, $COLUMN_LAST_USED_AT DESC",
            limit.toString()
        )
        cursor.use {
            val termIdx = it.getColumnIndexOrThrow(COLUMN_TERM)
            while (it.moveToNext()) {
                list.add(it.getString(termIdx))
            }
        }
        return list
    }

    /**
     * Retrieves all distinct terms as a flat string list for language model prompt injection.
     */
    fun getAllTerms(): List<String> {
        val list = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_TERM),
            null,
            null,
            null,
            null,
            "$COLUMN_FREQUENCY DESC, $COLUMN_LAST_USED_AT DESC",
            null
        )
        cursor.use {
            val termIdx = it.getColumnIndexOrThrow(COLUMN_TERM)
            while (it.moveToNext()) {
                list.add(it.getString(termIdx))
            }
        }
        return list
    }

    /**
     * Retrieves all items as structured objects for management UI.
     */
    fun getAllItems(searchQuery: String? = null): List<VocabularyItem> {
        val list = mutableListOf<VocabularyItem>()
        val db = readableDatabase
        val selection = if (!searchQuery.isNullOrBlank()) {
            "$COLUMN_TERM LIKE ? OR $COLUMN_TAG LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (!searchQuery.isNullOrBlank()) {
            val wild = "%$searchQuery%"
            arrayOf(wild, wild)
        } else {
            null
        }

        val cursor = db.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COLUMN_FREQUENCY DESC, $COLUMN_LAST_USED_AT DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToItem(it))
            }
        }
        return list
    }

    /**
     * Inserts a new term or increments existing term's frequency if already present.
     */
    fun insertOrIncrement(term: String, tag: String = "General"): Long {
        val cleanTerm = term.trim()
        if (cleanTerm.isEmpty()) return -1L

        val db = writableDatabase
        val now = System.currentTimeMillis()

        // Check if exists
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID, COLUMN_FREQUENCY),
            "$COLUMN_TERM = ? COLLATE NOCASE",
            arrayOf(cleanTerm),
            null,
            null,
            null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID))
                val currentFreq = it.getInt(it.getColumnIndexOrThrow(COLUMN_FREQUENCY))
                val cv = ContentValues().apply {
                    put(COLUMN_FREQUENCY, currentFreq + 1)
                    put(COLUMN_LAST_USED_AT, now)
                }
                db.update(TABLE_NAME, cv, "$COLUMN_ID = ?", arrayOf(id.toString()))
                id
            } else {
                val cv = ContentValues().apply {
                    put(COLUMN_TERM, cleanTerm)
                    put(COLUMN_TAG, tag.trim().ifEmpty { "General" })
                    put(COLUMN_FREQUENCY, 1)
                    put(COLUMN_LAST_USED_AT, now)
                    put(COLUMN_IS_DEFAULT, 0)
                }
                db.insert(TABLE_NAME, null, cv)
            }
        }
    }

    /**
     * Increments usage frequency for an exact or case-insensitive matched term.
     */
    fun incrementFrequency(term: String) {
        val cleanTerm = term.trim()
        if (cleanTerm.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            UPDATE $TABLE_NAME 
            SET $COLUMN_FREQUENCY = $COLUMN_FREQUENCY + 1, $COLUMN_LAST_USED_AT = ? 
            WHERE $COLUMN_TERM = ? COLLATE NOCASE
            """.trimIndent(),
            arrayOf(now, cleanTerm)
        )
    }

    /**
     * Deletes a vocabulary item by ID.
     */
    fun deleteItem(id: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_NAME, "$COLUMN_ID = ?", arrayOf(id.toString())) > 0
    }

    /**
     * Resets the entire table back to the curated default seed vocabulary.
     */
    fun resetToDefaults() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_NAME, null, null)
            seedDefaults(db)
            db.setTransactionSuccessful()
            Log.i(TAG, "Reset vocabulary database to defaults successfully")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Gets the total count of stored vocabulary items.
     */
    fun getCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun cursorToItem(cursor: Cursor): VocabularyItem {
        return VocabularyItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            term = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TERM)),
            tag = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAG)),
            frequency = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FREQUENCY)),
            lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_USED_AT)),
            isDefault = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_DEFAULT)) == 1
        )
    }
}
