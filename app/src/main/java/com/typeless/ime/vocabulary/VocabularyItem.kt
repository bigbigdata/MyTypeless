package com.typeless.ime.vocabulary

/**
 * VocabularyItem
 *
 * Represents a custom vocabulary entry or technical terminology used for speech-to-text
 * biasing and language model polishing.
 *
 * @property id Database primary key.
 * @property term The exact word or phrase (e.g. "PR", "deploy", "Notion", "brunch").
 * @property tag Category/context tag (e.g. "Tech", "Daily", "PM", "General").
 * @property frequency The number of times this term was matched in speech outputs.
 * @property lastUsedAt Timestamp in milliseconds when the term was last used.
 * @property isDefault Whether this entry is part of the pre-seeded default dictionary.
 */
data class VocabularyItem(
    val id: Long = 0,
    val term: String,
    val tag: String = "General",
    val frequency: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
)
