package top.toneup.word.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A vocabulary pack: a named, attributed group of words (the built-in demo packs or a
 * file the user imported).
 *
 * `isBuiltIn` distinguishes content shipped with the app from user content, which matters
 * for the licence screen and for "clear imported word books" operations.
 */
@Entity(
    tableName = "word_pack",
    indices = [Index("level"), Index("is_built_in")],
)
data class WordPackEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "source") val source: String = "",
    @ColumnInfo(name = "license") val license: String = "",
    @ColumnInfo(name = "version") val version: String = "1.0",
    @ColumnInfo(name = "is_built_in") val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "word_count") val wordCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

/**
 * One vocabulary entry.
 *
 * Design notes:
 *  * `normalized_word` carries the de-duplication identity together with `pack_id`; a unique
 *    index on that pair makes re-import idempotent *and* keeps the row id stable so the
 *    user's progress (which points at the row id) survives a re-import of the same pack.
 *    The same word may therefore exist in several packs, each with its own progress.
 *  * Vocabulary content and learning progress live in separate tables, so replacing a word
 *    list never destroys study history.
 *  * `lookup_state` / `meaning_source` / `en_definition` / `audio_url` / `lookup_attempts` /
 *    `last_lookup_at` / `user_note` are the §4.1 gateway columns added in migration 1→2
 *    (defaults are Kotlin-side only; the migration adds the columns with SQL DEFAULTs).
 */
@Entity(
    tableName = "word",
    indices = [
        Index(value = ["pack_id", "normalized_word"], unique = true, name = "index_word_pack_normalized_word"),
        Index(value = ["normalized_word"], name = "index_word_normalized"),
        Index("pack_id"),
        Index("word"),
        Index("level"),
    ],
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "pack_id") val packId: String?,
    /** Identifier supplied by the imported file, kept for round-tripping exports. */
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    @ColumnInfo(name = "word") val word: String,
    @ColumnInfo(name = "normalized_word") val normalizedWord: String,
    @ColumnInfo(name = "phonetic") val phonetic: String = "",
    @ColumnInfo(name = "part_of_speech") val partOfSpeech: String = "",
    /** JSON array of strings. */
    @ColumnInfo(name = "meanings") val meanings: String = "[]",
    @ColumnInfo(name = "example_sentence") val exampleSentence: String = "",
    @ColumnInfo(name = "example_translation") val exampleTranslation: String = "",
    /** JSON array of strings. */
    @ColumnInfo(name = "collocations") val collocations: String = "[]",
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "source") val source: String = "",
    @ColumnInfo(name = "license") val license: String = "",
    @ColumnInfo(name = "audio_uri") val audioUri: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    // --- migration 1→2 (§4.1 gateway columns) -------------------------------------------
    @ColumnInfo(name = "user_note") val userNote: String = "",
    /** NONE / OK / PENDING / FAILED — the meaning-completion state machine (D4). */
    @ColumnInfo(name = "lookup_state") val lookupState: String = "OK",
    /** ecdict / freecdict / mt / manual — `manual` is never overwritten by automation (D4). */
    @ColumnInfo(name = "meaning_source") val meaningSource: String = "",
    @ColumnInfo(name = "en_definition") val enDefinition: String = "",
    @ColumnInfo(name = "audio_url") val audioUrl: String = "",
    @ColumnInfo(name = "lookup_attempts") val lookupAttempts: Int = 0,
    @ColumnInfo(name = "last_lookup_at") val lastLookupAt: Long = 0L,
)

/**
 * Cross-pack meaning lookup cache (§4.2).
 *
 * Keyed by `normalized_word` globally: the same word is only ever fetched once, no matter how
 * many packs contain it (D3). The word row keeps the snapshot it adopted; this table is the
 * shared fetch result, including negative caching (`status = FAILED`) so a miss is not
 * hammered on every export.
 */
@Entity(
    tableName = "meaning_cache",
    primaryKeys = ["normalized_word"],
)
data class MeaningCacheEntity(
    @ColumnInfo(name = "normalized_word") val normalizedWord: String,
    /** JSON: phonetic / part of speech / zh+en meanings / example / synonyms / audio_url. */
    @ColumnInfo(name = "payload") val payload: String,
    /** ecdict / freecdict / mt */
    @ColumnInfo(name = "source") val source: String,
    /** OK / FAILED */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
